/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.core;

import lombok.extern.java.Log;
import org.apache.felix.framework.FrameworkFactory;
import org.apache.felix.framework.cache.BundleCache;
import org.apache.felix.framework.util.FelixConstants;
import org.apache.karaf.core.maven.Resolver;
import org.apache.karaf.core.module.BundleModule;
import org.apache.karaf.core.module.MicroprofileModule;
import org.apache.karaf.core.module.SpringBootModule;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@Log
public class Karaf {

    private KarafConfig config;
    private Framework framework = null;
    private Resolver resolver;
    private long start;

    private Karaf(KarafConfig config) {
        this.config = config;
    }

    public static Karaf build() {
        return new Karaf(KarafConfig.builder().build());
    }

    public static Karaf build(KarafConfig config) {
        return new Karaf(config);
    }

    public void init() throws Exception {
        start = System.currentTimeMillis();

        resolver = new Resolver(config.mavenRepositories());

        if (System.getenv("KARAF_LOG_FORMAT") != null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", System.getenv("KARAF_LOG_FORMAT"));
        }
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT.%1$tN %4$s [ %2$s ] : %5$s%6$s%n");
        }

        log.info("\n" +
                "        __ __                  ____      \n" +
                "       / //_/____ __________ _/ __/      \n" +
                "      / ,<  / __ `/ ___/ __ `/ /_        \n" +
                "     / /| |/ /_/ / /  / /_/ / __/        \n" +
                "    /_/ |_|\\__,_/_/   \\__,_/_/         \n" +
                "\n" +
                "  Apache Karaf (5.0.0-SNAPSHOT)\n");

        log.info("Base directory: " + this.config.homeDirectory());
        log.info("Cache directory: " + this.config.cacheDirectory());
        Map<String, Object> config = new HashMap<>();
        config.put(Constants.FRAMEWORK_STORAGE, this.config.cacheDirectory());
        if (this.config.clearCache()) {
            config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        }
        config.put(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, "100");
        config.put(FelixConstants.LOG_LEVEL_PROP, "3");
        config.put(BundleCache.CACHE_ROOTDIR_PROP, this.config.cacheDirectory());
        String bootDelegation = loadBootDelegation();
        if (bootDelegation != null) {
            log.info("Using predefined boot delegation");
            config.put(Constants.FRAMEWORK_BOOTDELEGATION, bootDelegation);
        }
        String systemPackages = loadSystemPackages();
        if (systemPackages != null) {
            log.info("Using predefined system packages");
            config.put(Constants.FRAMEWORK_SYSTEMPACKAGES, systemPackages);
        }

        FrameworkFactory frameworkFactory = new FrameworkFactory();
        framework = frameworkFactory.newFramework(config);

        try {
            framework.init();
            framework.start();
        } catch (BundleException e) {
            throw new RuntimeException(e);
        }

        FrameworkStartLevel sl = framework.adapt(FrameworkStartLevel.class);
        sl.setInitialBundleStartLevel(this.config.defaultBundleStartLevel());

        if (framework.getBundleContext().getBundles().length != 1) {
            loadModules();
            loadExtensions();
        }
    }

    public void start() {
        long now = System.currentTimeMillis();

        log.info(getStartedMessage(start, now));
    }

    private String getStartedMessage(long start, long now) {
        StringBuilder message = new StringBuilder();
        message.append("Started in ");
        message.append((now - start) / 1000.0);
        message.append(" seconds");
        try {
            double uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
            message.append(" (JVM running for ").append(uptime).append(")");
        } catch (Throwable e) {
            // no-op
        }
        return message.toString();
    }

    private String loadFile(String resource) {
        try {
            try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(resource)) {
                if (inputStream == null) {
                    throw new IllegalStateException(resource + " not found");
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    String line;
                    StringBuilder builder = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("#") && !line.startsWith("//")) {
                            builder.append(line);
                        }
                    }
                    return builder.toString();
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Can't load " + resource, e);
        }
        return null;
    }

    private String loadBootDelegation() {
        return loadFile("etc/boot.delegation");
    }

    private String loadSystemPackages() {
        return loadFile("etc/system.packages");
    }

    public void loadModules() throws Exception {
        log.info("Loading KARAF_MODULES env");
        String modulesEnv = System.getenv("KARAF_MODULES");
        if (modulesEnv != null) {
            String[] modulesSplit = modulesEnv.split(",");
            for (String module : modulesSplit) {
                addModule(module);
            }
        }
    }

    public void loadExtensions() throws Exception {
        log.info("Loading KARAF_EXTENSIONS env");
        String extensionsEnv = System.getenv("KARAF_EXTENSIONS");
        if (extensionsEnv != null) {
            String[] extensionsSplit = extensionsEnv.split(",");
            for (String extension : extensionsSplit) {
                addExtension(extension);
            }
        }
    }

    public void addModule(String url) throws Exception {
        log.info("Installing module " + url);

        url = resolver.resolve(url);

        BundleModule bundleModule = new BundleModule(framework, this.config.defaultBundleStartLevel());
        if (bundleModule.canHandle(url)) {
            bundleModule.add(url);
        }

        SpringBootModule springBootModule = new SpringBootModule();
        if (springBootModule.canHandle(url)) {
            springBootModule.add(url);
        }

        MicroprofileModule microprofileModule = new MicroprofileModule();
        if (microprofileModule.canHandle(url)) {
            microprofileModule.add(url);
        }
    }

    public void addExtension(String url) throws Exception {
        log.info("Loading extension from " + url);
        org.apache.karaf.core.extension.Loader.load(resolver.resolve(url), framework.getBundleContext());
    }

    public BundleContext getBundleContext() {
        return framework.getBundleContext();
    }

}