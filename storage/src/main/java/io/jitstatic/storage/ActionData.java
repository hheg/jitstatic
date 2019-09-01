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
    private final StorageEvent type;
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

    static ActionData addKey(String key, ObjectStreamProvider data, MetaData metaData, CommitMetaData commitMetaData) {
        return new ActionData(StorageEvent.ADD_KEY, key, data, metaData, commitMetaData);
    }

    private ActionData(StorageEvent addKeyEvent, String key2, ObjectStreamProvider data2, MetaData metaData2, CommitMetaData commitMetaData2) {
        this.type = addKeyEvent;
        this.key = key2;
        this.data = data2;
        this.metaData = metaData2;
        this.commitMetaData = commitMetaData2;
    }

    static ActionData updateKey(String key, ObjectStreamProvider data, String oldVersion, CommitMetaData commitMetaData) {
        return new ActionData(StorageEvent.UPDATE_KEY, key, data, oldVersion, commitMetaData);
    }

    private ActionData(StorageEvent updateKey, String key, ObjectStreamProvider data, String oldVersion, CommitMetaData commitMetaData) {
        this.type = updateKey;
        this.data = data;
        this.key = key;
        this.oldVersion = oldVersion;
        this.commitMetaData = commitMetaData;
    }

    static ActionData deleteKey(String key, CommitMetaData commitMetaData) {
        return new ActionData(StorageEvent.DELETE_KEY, key, commitMetaData);
    }

    private ActionData(StorageEvent deleteKey, String key, CommitMetaData commitMetaData) {
        this.type = deleteKey;
        this.key = key;
        this.commitMetaData = commitMetaData;
    }

    static ActionData updateMetakey(String key, MetaData metaData, String oldMetaDataVersion, CommitMetaData commitMetaData) {
        return new ActionData(StorageEvent.UPDATE_METAKEY, key, metaData, oldMetaDataVersion, commitMetaData);
    }

    private ActionData(StorageEvent updateMetakey, String key, MetaData metaData, String oldMetaDataVersion, CommitMetaData commitMetaData) {
        this.type = updateMetakey;
        this.key = key;
        this.metaData = metaData;
        this.oldVersion = oldMetaDataVersion;
        this.commitMetaData = commitMetaData;
    }

    static ActionData addUser(String userKeyPath, String userName, UserData userData) {
        return new ActionData(StorageEvent.ADD_USER, userKeyPath, userName, userData);
    }

    private ActionData(StorageEvent addUser, String userKeyPath, String userName, UserData userData) {
        this.type = addUser;
        this.key = userKeyPath;
        this.userName = userName;
        this.userData = userData;
    }

    static ActionData updateUser(String userKeyPath, String userName, UserData userData, String oldVersion) {
        return new ActionData(StorageEvent.UPDATE_USER, userKeyPath, userName, userData, oldVersion);
    }

    private ActionData(StorageEvent updateUser, String userKeyPath, String userName, UserData userData, String oldVersion) {
        this.type = updateUser;
        this.key = userKeyPath;
        this.userName = userName;
        this.userData = userData;
        this.oldVersion = oldVersion;
    }

    static ActionData deleteUser(String userKeyPath, String userName) {
        return new ActionData(StorageEvent.DELETE_USER, userKeyPath, userName);
    }

    private ActionData(StorageEvent deleteUser, String userKeyPath, String userName) {
        this.type = deleteUser;
        this.key = userKeyPath;
        this.userName = userName;
    }

    private ActionData() {
        this.type = StorageEvent.WRITE_REPO;
    }

    public StorageEvent getType() {
        return type;
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
