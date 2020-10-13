/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */
package org.objectweb.asm.idea.plugin.common;

/**
 * Created by IntelliJ IDEA.
 * User: cedric
 * Date: 18/01/11
 * Time: 06:58
 * Updated by: Kamiel
 */

/**
 * Constants used in various places of the code.
 */
public abstract class Constants {
    public static final String PLUGIN_WINDOW_NAME = "ASMPlugin";
    public static final String FILE_NAME = "asm-plugin";
    public static final String NO_CLASS_FOUND = "// couldn't generate bytecode view, no .class file found";
    public static final String COMPONENT_NAME = "ASMIdeaPluginConfiguration";

    private Constants() {

    }
}
