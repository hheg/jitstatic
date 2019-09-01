package io.jitstatic.storage;

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

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.storage.events.StorageEvent;

public class ActionData {
    public static final ActionData PLACEHOLDER = new ActionData();
    private StorageEvent type;
    private String key;
    private String ref;
    private String oldVersion;
    private ObjectStreamProvider data;
    private MetaData metaData;
    private CommitMetaData commitMetaData;
    private String userName;
    private UserData userData;
    private String oldId;
    private String tipId;

    public ActionData(String key, ObjectStreamProvider data, MetaData metaData, CommitMetaData commitMetaData) {
        this.type = StorageEvent.ADD_KEY;
        this.key = key;
        this.data = data;
        this.metaData = metaData;
        this.commitMetaData = commitMetaData;
    }

    public ActionData(String key, ObjectStreamProvider data, String oldVersion, CommitMetaData commitMetaData) {
        this.type = StorageEvent.UPDATE_KEY;
        this.data = data;
        this.key = key;
        this.oldVersion = oldVersion;
        this.commitMetaData = commitMetaData;
    }

    public ActionData(String key, CommitMetaData commitMetaData) {
        this.type = StorageEvent.DELETE_KEY;
        this.key = key;
        this.commitMetaData = commitMetaData;
    }

    public ActionData(String key, MetaData metaData, String oldMetaDataVersion, CommitMetaData commitMetaData) {
        this.type = StorageEvent.UPDATE_METAKEY;
        this.key = key;
        this.metaData = metaData;
        this.oldVersion = oldMetaDataVersion;
        this.commitMetaData = commitMetaData;
    }

    public ActionData(String userKeyPath, String userName, UserData userData) {
        this.type = StorageEvent.ADD_USER;
        this.key = userKeyPath;
        this.userName = userName;
        this.userData = userData;
    }

    public ActionData(String userKeyPath, String userName, UserData userData, String oldVersion) {
        this.type = StorageEvent.UPDATE_USER;
        this.key = userKeyPath;
        this.userName = userName;
        this.userData = userData;
        this.oldVersion = oldVersion;
    }

    public ActionData(String userKeyPath, String userName) {
        this.type = StorageEvent.DELETE_USER;
        this.key = userKeyPath;
        this.userName = userName;
    }

    public ActionData(ObjectStreamProvider data, String oldId, String tipId) {
        this.type = StorageEvent.WRITE_REPO;
        this.key = null;
        this.data = data;
        this.oldId = oldId;
        this.tipId = tipId;
    }

    public ActionData() {
        // NOOP
    }

    public StorageEvent getType() {
        return type;
    }

    public void setType(StorageEvent type) {
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getOldVersion() {
        return oldVersion;
    }

    public void setOldVersion(String oldVersion) {
        this.oldVersion = oldVersion;
    }

    public ObjectStreamProvider getData() {
        return data;
    }

    public void setData(ObjectStreamProvider data) {
        this.data = data;
    }

    public MetaData getMetaData() {
        return metaData;
    }

    public void setMetaData(MetaData metaData) {
        this.metaData = metaData;
    }

    public CommitMetaData getCommitMetaData() {
        return commitMetaData;
    }

    public void setCommitMetaData(CommitMetaData commitMetaData) {
        this.commitMetaData = commitMetaData;
    }

    public UserData getUserData() {
        return userData;
    }

    public void setUserData(UserData userData) {
        this.userData = userData;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getOldId() {
        return oldId;
    }

    public void setOldId(String oldId) {
        this.oldId = oldId;
    }

    public String getTipId() {
        return tipId;
    }

    public void setTipId(String tipId) {
        this.tipId = tipId;
    }

}
