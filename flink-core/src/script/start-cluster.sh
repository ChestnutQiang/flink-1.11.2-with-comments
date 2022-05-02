#!/usr/bin/env bash
################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

# 这个脚本相对较简单，总体流程就是先调用config.sh加载配置文件，
# 然后判断我们配置的是HA模式还是单机模式，分别执行不同的逻辑，
# 对应调用jobmanager.sh传不同的参数，启动jobmanager，
# 最后启动TashManager。

bin=`dirname "$0"`
bin=`cd "$bin"; pwd`

# 先调用config.sh读取配置文件
. "$bin"/config.sh

# 启动JobManager，分为HA模式和单机模式
# Start the JobManager instance(s)
shopt -s nocasematch

# 这里的HIGH_AVAILABILITY就是flink.yaml中配置的high-availability
if [[ $HIGH_AVAILABILITY == "zookeeper" ]]; then
    # HA Mode
    readMasters

    echo "Starting HA cluster with ${#MASTERS[@]} masters."

    for ((i=0;i<${#MASTERS[@]};++i)); do
        master=${MASTERS[i]}
        webuiport=${WEBUIPORTS[i]}

        if [ ${MASTERS_ALL_LOCALHOST} = true ] ; then
            "${FLINK_BIN_DIR}"/jobmanager.sh start "${master}" "${webuiport}"
        else
            ssh -n $FLINK_SSH_OPTS $master -- "nohup /bin/bash -l \"${FLINK_BIN_DIR}/jobmanager.sh\" start ${master} ${webuiport} &"
        fi
    done

else
    echo "Starting cluster."

    # Start single JobManager on this machine
    "$FLINK_BIN_DIR"/jobmanager.sh start
fi
shopt -u nocasematch

# 启动JobManager之后启动TaskManager，这个方法在config.sh中
# Start TaskManager instance(s)
TMWorkers start


