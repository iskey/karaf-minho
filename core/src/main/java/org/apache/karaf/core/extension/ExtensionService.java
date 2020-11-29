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
package org.apache.karaf.core.extension;

import lombok.extern.java.Log;
import org.apache.karaf.core.Karaf;
import org.apache.karaf.core.model.ModelLoader;
import org.apache.karaf.core.model.Module;
import org.apache.karaf.core.model.Extension;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

@Log
public class ExtensionService {

    private static Extension read(String url) throws Exception {
        String resolved = Karaf.get().getResolver().resolve(url);
        if (resolved == null) {
            throw new IllegalArgumentException(url + " not found");
        }
        if (resolved.startsWith("file:")) {
            resolved = resolved.substring("file:".length());
        }
        InputStream inputStream;
        try {
            JarFile jarFile = new JarFile(new File(resolved));
            ZipEntry entry = jarFile.getEntry("KARAF-INF/extension.json");
            if (entry == null) {
                throw new IllegalArgumentException(url + " is not a Karaf extension");
            }
            inputStream = jarFile.getInputStream(entry);
        } catch (ZipException zipException) {
            log.log(Level.FINE, url + " is not a jar file");
            inputStream = new FileInputStream(new File(resolved));
        }
        return ModelLoader.read(inputStream);
    }

    public static void load(String url) throws Exception {
        log.info("Loading extension from " + url);
        if (Karaf.extensions.get(url) != null) {
            log.info("Extension " + url + " already installed");
            return;
        }
        Extension extension = read(url);
        log.info("Loading " + extension.getName() + "/" + extension.getVersion() + " extension");
        if (extension.getExtension() != null) {
            for (String innerExtension : extension.getExtension()) {
                load(innerExtension);
            }
        }
        if (extension.getModule() != null) {
            for (Module module : extension.getModule()) {
                String moduleUrl = Karaf.get().getResolver().resolve(module.getLocation());
                if (moduleUrl == null) {
                    throw new IllegalArgumentException("Module " + module.getLocation() + " not found");
                } else {
                    Karaf.get().addModule(module.getLocation());
                }
            }
        }
        // extension can be a module itself
        Karaf.get().addModule(url);
        // update extensions store
        Karaf.extensions.put(url, extension);
    }

    public static void remove(String url, boolean recursive) throws Exception {
        log.info("Removing extension " + url);
        if (Karaf.extensions.get(url) == null) {
            return;
        }
        if (recursive) {
            Extension extension = read(url);
            if (extension.getExtension() != null) {
                for (String innerExtension : extension.getExtension()) {
                    remove(innerExtension, recursive);
                }
            }
            if (extension.getModule() != null) {
                for (Module module : extension.getModule()) {
                    Karaf.get().removeModule(module.getLocation());
                }
            }
        }
        Karaf.extensions.remove(url);
    }

}