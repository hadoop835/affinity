/*
 * Copyright 2016-2018 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.core.config;

import com.typesafe.config.Config;

public class CfgCls<B> extends Cfg<Class<? extends B>> {

    private final Class<? extends B> cls;

    public CfgCls(Class<? extends B> cls) {
        this.cls = cls;
    }

    @Override
    public CfgCls<B> apply(Config config) {
        String fqn = listPos > -1 ? config.getStringList(relPath).get(listPos) : config.getString(relPath);
        try {
            setValue(Class.forName(fqn).asSubclass(cls));
            return this;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(fqn + " is not an instance of " + cls);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String parameterInfo() {
        return "fqn";
    }

    @Override
    public String defaultInfo() {
        return isDefined() ? apply().getName().toString() : "-";
    }

}
