/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.onosproject.cord.gui;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.onosproject.cord.gui.model.Bundle;
import org.onosproject.cord.gui.model.BundleDescriptor;
import org.onosproject.cord.gui.model.BundleFactory;
import org.onosproject.cord.gui.model.JsonFactory;
import org.onosproject.cord.gui.model.SubscriberUser;
import org.onosproject.cord.gui.model.UserFactory;
import org.onosproject.cord.gui.model.XosFunction;
import org.onosproject.cord.gui.model.XosFunctionDescriptor;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * In memory cache of the model of the subscriber's account.
 */
public class CordModelCache extends JsonFactory {

    private static final String BUNDLE = "bundle";
    private static final String USERS = "users";


    // faked for the demo
    private static final int SUBSCRIBER_ID = 92;
    private static final String MAC_1 = "010203040506";
    private static final String MAC_2 = "010203040507";
    private static final String MAC_3 = "010203040508";
    private static final String MAC_4 = "010203040509";

    private Bundle currentBundle;

    // NOTE: use a tree map to maintain sorted order by user ID
    private final Map<Integer, SubscriberUser> userMap =
            new TreeMap<Integer, SubscriberUser>();

    /**
     * Constructs a model cache, initializing it with basic bundle.
     */
    CordModelCache() {
        currentBundle = new Bundle(BundleFactory.BASIC_BUNDLE);
        initUsers();
    }

    /**
     * Used to initialize users for the demo. These are currently fake.
     */
    public void initUsers() {
        userMap.put(1, createUser(1, "Mom's MacBook", MAC_1));
        userMap.put(2, createUser(2, "Dad's iPad", MAC_2));
        userMap.put(3, createUser(3, "Dick's laptop", MAC_3));
        userMap.put(4, createUser(4, "Jane's laptop", MAC_4));
    }

    private SubscriberUser createUser(int uid, String name, String mac) {
        SubscriberUser user = new SubscriberUser(uid, name, mac);
        for (XosFunction f: currentBundle.functions()) {
            user.setMemento(f.descriptor(), f.createMemento());
        }
        return user;
    }

    /**
     * Returns the currently selected bundle.
     *
     * @return current bundle
     */
    public Bundle getCurrentBundle() {
        return currentBundle;
    }

    /**
     * Sets a new bundle.
     *
     * @param bundleId bundle identifier
     * @throws IllegalArgumentException if bundle ID is unknown
     */
    public void setCurrentBundle(String bundleId) {
        BundleDescriptor bd = BundleFactory.bundleFromId(bundleId);
        currentBundle = new Bundle(bd);
        // update the user mementos
        for (SubscriberUser user: userMap.values()) {
            user.clearMementos();
            for (XosFunction f: currentBundle.functions()) {
                user.setMemento(f.descriptor(), f.createMemento());
            }
        }

        XosManager.INSTANCE.setNewBundle(SUBSCRIBER_ID, currentBundle);
    }


    /**
     * Returns the list of current users for this subscriber account.
     *
     * @return the list of users
     */
    public List<SubscriberUser> getUsers() {
        return ImmutableList.copyOf(userMap.values());
    }

    /**
     * Applies a function parameter change for a user.
     *
     * @param userId user identifier
     * @param funcId function identifier
     * @param param function parameter to change
     * @param value new value for function parameter
     */
    public void applyPerUserParam(String userId, String funcId,
                                  String param, String value) {

        int uid = Integer.parseInt(userId);
        SubscriberUser user = userMap.get(uid);
        checkNotNull(user, "unknown user id: " + uid);

        XosFunctionDescriptor xfd =
                XosFunctionDescriptor.valueOf(funcId.toUpperCase());

        XosFunction func = currentBundle.findFunction(xfd);
        checkNotNull(func, "function not part of bundle: " + funcId);

        func.applyParam(user, param, value);
        XosManager.INSTANCE.apply(SUBSCRIBER_ID, func, user);
    }

    // =============

    private ArrayNode userJsonArray() {
        ArrayNode userList = arrayNode();
        for (SubscriberUser user: userMap.values()) {
            userList.add(UserFactory.toObjectNode(user));
        }
        return userList;
    }

    // ============= generate JSON for GUI rest calls..

    /**
     * Returns the dashboard page data as JSON.
     *
     * @return dashboard page JSON data
     */
    public String jsonDashboard() {
        ObjectNode root = objectNode();
        root.put(BUNDLE, currentBundle.descriptor().displayName());
        root.set(USERS, userJsonArray());
        return root.toString();
    }

    /**
     * Returns the bundle page data as JSON.
     *
     * @return bundle page JSON data
     */
    public String jsonBundle() {
        return BundleFactory.toJson(currentBundle);
    }

    /**
     * Returns the users page data as JSON.
     *
     * @return users page JSON data
     */
    public String jsonUsers() {
        ObjectNode root = objectNode();
        root.set(USERS, userJsonArray());
        return root.toString();
    }

    /**
     * Singleton instance.
     */
    public static final CordModelCache INSTANCE = new CordModelCache();
}
