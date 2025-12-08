/*
 * Copyright (C) 2025 OpenFDE
 *
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
 */

package com.android.internal.policy;

import com.android.internal.policy.IAppSystemBarController;



/**
* An interface to notify the task caption service that a task key is pressed
* @hide
*/
oneway interface ITaskCaptionOperationService {

    /**
     * @param taskId  task id of task to operate
     *
     * @param operationType
     *          0 close
     *          1 back
     *          2 fullscreen
     *          3 minimize
     *          4 maximize
     *          ... maybe more
     */
    void executeTaskOperation(int taskId, int operationType);

    void registerSystemBarController(int taskId, IAppSystemBarController controller);

    void unregisterSystemBarController(int taskId);

}

