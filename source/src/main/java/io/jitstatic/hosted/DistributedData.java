package io.jitstatic.hosted;

import java.io.IOException;
import java.io.OutputStream;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

public class DistributedData {
    private CheckedConsumer<OutputStream, IOException> data;
    private String tip;
    private String oldTip;
    
    public DistributedData(CheckedConsumer<OutputStream,IOException> data, String tip, String oldTip) {
        this.data = data;
        this.tip = tip;
        this.oldTip = oldTip;
    }

    public CheckedConsumer<OutputStream, IOException> getData() {
        return data;
    }

    public void setData(CheckedConsumer<OutputStream, IOException> data) {
        this.data = data;
    }

    public String getTip() {
        return tip;
    }

    public void setTip(String tip) {
        this.tip = tip;
    }

    public String getOld() {
        return oldTip;
    }

    public void setOld(String old) {
        this.oldTip = old;
    }
}
