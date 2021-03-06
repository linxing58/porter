/*
 * Copyright ©2018 vbill.cn.
 * <p>
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
 * </p>
 */

package cn.vbill.middleware.porter.task.worker;

import cn.vbill.middleware.porter.common.alert.AlertReceiver;
import cn.vbill.middleware.porter.common.cluster.ClusterProviderProxy;
import cn.vbill.middleware.porter.common.cluster.command.StatisticUploadCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskPositionQueryCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskRegisterCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskStatCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskStatQueryCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskStopCommand;
import cn.vbill.middleware.porter.common.cluster.command.TaskStoppedByErrorCommand;
import cn.vbill.middleware.porter.common.cluster.data.DCallback;
import cn.vbill.middleware.porter.common.cluster.data.DObject;
import cn.vbill.middleware.porter.common.cluster.data.DTaskStat;
import cn.vbill.middleware.porter.common.exception.TaskStopTriggerException;
import cn.vbill.middleware.porter.common.exception.WorkResourceAcquireException;
import cn.vbill.middleware.porter.common.statistics.NodeLog;
import cn.vbill.middleware.porter.common.statistics.TaskPerformance;
import cn.vbill.middleware.porter.common.util.MachineUtils;
import cn.vbill.middleware.porter.core.NodeContext;
import cn.vbill.middleware.porter.core.consumer.DataConsumer;
import cn.vbill.middleware.porter.core.loader.DataLoader;
import cn.vbill.middleware.porter.core.task.StageJob;
import cn.vbill.middleware.porter.core.task.StageType;
import cn.vbill.middleware.porter.core.task.TableMapper;
import cn.vbill.middleware.porter.task.TaskController;
import cn.vbill.middleware.porter.task.alert.AlertJob;
import cn.vbill.middleware.porter.task.extract.ExtractJob;
import cn.vbill.middleware.porter.task.load.LoadJob;
import cn.vbill.middleware.porter.task.select.SelectJob;
import cn.vbill.middleware.porter.task.transform.TransformJob;
import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月21日 14:48
 * @version: V1.0
 * @review: zhangkewei[zhang_kw@suixingpay.com]/2017年12月21日 14:48
 */
public class TaskWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskWork.class);
    private final AtomicBoolean statAtomicBoolean = new AtomicBoolean(false);

    private final String taskId;

    //单消费泳道
    private final DataConsumer dataConsumer;
    private final DataLoader dataLoader;
    /**
     * stageType -> job
     */
    private final Map<StageType, StageJob> stageJobs;
    private final String basicThreadName;

    /**
     * schema_table -> TaskStat
     */
    private final Map<String, DTaskStat> stats;
    private final Map<String, TableMapper> mappers;
    private final TaskWorker worker;

    private final List<AlertReceiver> receivers;

    /**
     * 触发任务停止标识，生命周期内，仅有一次
     */
    private final AtomicBoolean stopTrigger = new AtomicBoolean(false);

    public TaskWork(DataConsumer dataConsumer, DataLoader dataLoader, String taskId, List<AlertReceiver> receivers,
                    TaskWorker worker, long positionCheckInterval, long alarmPositionCount) throws Exception {
        this.dataConsumer = dataConsumer;
        this.dataLoader = dataLoader;
        basicThreadName = "TaskWork-[taskId:" + taskId + "]-[consumer:" + dataConsumer.getSwimlaneId() + "]";
        this.taskId = taskId;
        this.stats = new ConcurrentHashMap<>();
        this.mappers = new ConcurrentHashMap<>();
        this.worker = worker;
        this.receivers = Collections.unmodifiableList(receivers);
        TaskWork work = this;
        stageJobs = new LinkedHashMap<>();

        stageJobs.put(StageType.SELECT, new SelectJob(work));
        stageJobs.put(StageType.EXTRACT, new ExtractJob(work));
        stageJobs.put(StageType.TRANSFORM, new TransformJob(work));
        stageJobs.put(StageType.LOAD, new LoadJob(work, positionCheckInterval, alarmPositionCount));
        /**
         * 源端数据源支持元数据查询
         */
        if (dataConsumer.supportMetaQuery()) {
            stageJobs.put(StageType.DB_CHECK, new AlertJob(work));
        }

        //从集群模块获取任务状态统计信息
        ClusterProviderProxy.INSTANCE.broadcast(new TaskStatQueryCommand(taskId, dataConsumer.getSwimlaneId(), new DCallback() {
            @Override
            public void callback(List<DObject> objects) {
                for (DObject object : objects) {
                    DTaskStat stat = (DTaskStat) object;
                    getDTaskStat(stat.getSchema(), stat.getTable());
                }
            }
        }));
    }

    /**
     * stop
     *
     * @date 2018/8/9 下午2:15
     * @param: []
     * @return: void
     */
    protected void stop() {
        if (statAtomicBoolean.compareAndSet(true, false)) {
            try {
                LOGGER.info("终止执行任务[{}-{}]", taskId, dataConsumer.getSwimlaneId());
                //终止阶段性工作,需要
                for (Map.Entry<StageType, StageJob> jobs : stageJobs.entrySet()) {
                    //确保每个阶段工作都被执行
                    try {
                        LOGGER.info("终止执行工作[{}-{}-{}]", taskId, dataConsumer.getSwimlaneId(), jobs.getValue().getClass().getSimpleName());
                        jobs.getValue().stop();
                    } catch (Throwable e) {
                        LOGGER.error("终止执行工作[{}-{}-{}]失败", taskId, dataConsumer.getSwimlaneId(), jobs.getValue().getClass().getSimpleName(), e);
                    }
                }
                try {
                    //上传消费进度
                    submitStat();
                } catch (Exception e) {
                    NodeLog.upload(NodeLog.LogType.TASK_LOG, taskId, dataConsumer.getSwimlaneId(), "停止上传消费进度失败:" + e.getMessage());
                }
                try {
                    //广播任务结束消息
                    ClusterProviderProxy.INSTANCE.broadcast(new TaskStopCommand(taskId, dataConsumer.getSwimlaneId()));
                } catch (Exception e) {
                    NodeLog.upload(NodeLog.LogType.TASK_LOG, taskId, dataConsumer.getSwimlaneId(), "广播TaskStopCommand失败:" + e.getMessage());
                }
            } catch (Exception e) {
                e.printStackTrace();
                NodeLog.upload(NodeLog.LogType.TASK_LOG, taskId, dataConsumer.getSwimlaneId(), "任务关闭失败:" + e.getMessage());
                LOGGER.error("终止执行任务[{}-{}]异常", taskId, dataConsumer.getSwimlaneId(), e);
            } finally {
                NodeContext.INSTANCE.releaseWork();
            }
        }
    }

    /**
     * start
     *
     * @date 2018/8/9 下午2:15
     * @param: []
     * @return: void
     */
    protected void start() throws Exception {
        if (statAtomicBoolean.compareAndSet(false, true)) {
            LOGGER.info("开始执行任务[{}-{}]", taskId, dataConsumer.getSwimlaneId());
            //申请work资源
            if (!NodeContext.INSTANCE.acquireWork()) {
                throw new WorkResourceAcquireException("未申请到可供任务执行的资源");
            }


            //会抛出分布式锁任务抢占异常
            ClusterProviderProxy.INSTANCE.broadcast(new TaskRegisterCommand(taskId, dataConsumer.getSwimlaneId()));
            //开始阶段性工作
            for (Map.Entry<StageType, StageJob> jobs : stageJobs.entrySet()) {
                jobs.getValue().start();
            }

            LOGGER.info("开始获取任务消费泳道[{}-{}]上次同步点", taskId, dataConsumer.getSwimlaneId());
            //获取上次任务进度
            ClusterProviderProxy.INSTANCE.broadcast(new TaskPositionQueryCommand(taskId, dataConsumer.getSwimlaneId(), new DCallback() {
                @Override
                @SneakyThrows(TaskStopTriggerException.class)
                public void callback(String position) {
                    LOGGER.info("获取任务消费泳道[{}-{}]上次同步点->{}，通知SelectJob", taskId, dataConsumer.getSwimlaneId(), position);
                    position = StringUtils.isBlank(position) ? dataConsumer.getInitiatePosition() : position;
                    LOGGER.info("计算任务消费泳道[{}-{}]最终同步点->{}，通知SelectJob", taskId, dataConsumer.getSwimlaneId(), position);
                    dataConsumer.initializePosition(taskId, dataConsumer.getSwimlaneId(), position);
                }
            }));
        }
    }


    public String getBasicThreadName() {
        return basicThreadName;
    }

    /**
     * 等待Event
     *
     * @date 2018/8/9 下午2:15
     * @param: [type]
     * @return: T
     */
    public <T> T waitEvent(StageType type) throws Exception {
        return stageJobs.get(type).output();
    }

    /**
     * 等待Sequence
     *
     * @date 2018/8/9 下午2:16
     * @param: []
     * @return: T
     */
    public <T> T waitSequence() {
        return ((ExtractJob) stageJobs.get(StageType.EXTRACT)).getNextSequence();
    }

    /**
     * isPoolEmpty
     *
     * @date 2018/8/9 下午2:16
     * @param: [type]
     * @return: boolean
     */
    public boolean isPoolEmpty(StageType type) {
        return stageJobs.get(type).isPoolEmpty();
    }

    public String getTaskId() {
        return taskId;
    }

    /**
     * submitStat
     *
     * @date 2018/8/9 下午2:16
     * @param: []
     * @return: void
     */
    public void submitStat() {
        stats.forEach((s, stat) -> {
            LOGGER.debug("stat before submit:{}", JSON.toJSONString(stat));
            //多线程访问情况下（目前是两个线程:状态上报线程、任务状态更新线程），获取JOB的运行状态。
            DTaskStat newStat = null;
            synchronized (stat) {
                newStat = stat.snapshot(DTaskStat.class);
                stat.reset();
            }
            LOGGER.debug("stat snapshot:{}", JSON.toJSONString(newStat));
            try {
                ClusterProviderProxy.INSTANCE.broadcast(new TaskStatCommand(newStat, new DCallback() {
                    @Override
                    public void callback(DObject object) {
                        DTaskStat remoteData = (DTaskStat) object;
                        synchronized (stat) {
                            if (stat.getUpdateStat().compareAndSet(false, true)) {
                                //最后检查点
                                if (null == stat.getLastCheckedTime()) {
                                    stat.setLastLoadedDataTime(remoteData.getLastLoadedDataTime());
                                }
                                //最初启动时间
                                if (null != remoteData.getRegisteredTime()) {
                                    stat.setRegisteredTime(remoteData.getRegisteredTime());
                                }
                            }
                        }
                    }
                }));
            } catch (Throwable e) {
                NodeLog.upload(NodeLog.LogType.TASK_LOG, taskId, dataConsumer.getSwimlaneId(), "上传任务状态信息失败:" + e.getMessage());
            }

            //上传统计
            try {
                //TaskPerformance
                if (NodeContext.INSTANCE.isUploadStatistic()) {
                    ClusterProviderProxy.INSTANCE.broadcast(new StatisticUploadCommand(new TaskPerformance(newStat)));
                }
            } catch (Throwable e) {
                NodeLog.upload(NodeLog.LogType.TASK_LOG, taskId, dataConsumer.getSwimlaneId(), "上传任务统计信息失败:" + e.getMessage());
            }
        });
    }

    /**
     * 获取TableMapper
     *
     * @date 2018/8/9 下午2:17
     * @param: [schema, table]
     * @return: cn.vbill.middleware.porter.core.task.TableMapper
     */
    public TableMapper getTableMapper(String schema, String table) {
        String key = schema + "." + table;
        TableMapper mapper = mappers.computeIfAbsent(key, s -> {
            TableMapper tmp = null;
            String mapperKey = taskId + "_" + schema + "_" + table;
            tmp = worker.getTableMapper().get(mapperKey);
            if (null == tmp) {
                mapperKey = taskId + "__" + table;
                tmp = worker.getTableMapper().get(mapperKey);
            }
            if (null == tmp) {
                mapperKey = taskId + "_" + schema + "_";
                tmp = worker.getTableMapper().get(mapperKey);
            }
            if (null == tmp) {
                mapperKey = taskId + "_" + "_";
                tmp = worker.getTableMapper().get(mapperKey);
            }
            return tmp;
        });
        return mapper;
    }

    /**
     * 获取TaskStat
     *
     * @date 2018/8/9 下午2:18
     * @param: [schema, table]
     * @return: cn.vbill.middleware.porter.common.cluster.data.DTaskStat
     */
    public DTaskStat getDTaskStat(String schema, String table) {
        String key = schema + "." + table;
        DTaskStat stat = stats.computeIfAbsent(key, s ->
                new DTaskStat(taskId, null, dataConsumer.getSwimlaneId(), schema, table)
        );
        return stat;
    }

    public List<DTaskStat> getStats() {
        return Collections.unmodifiableList(stats.values().stream().collect(Collectors.toList()));
    }

    /**
     * stopAndAlarm
     *
     * @date 2018/8/9 下午2:18
     * @param: [notice]
     * @return: void
     */
    public void stopAndAlarm(final String notice) {
        if (stopTrigger.compareAndSet(false, true)) {
            new Thread("suixingpay-TaskStopByErrorTrigger-stopTask-" + taskId + "-" + dataConsumer.getSwimlaneId()) {
                @Override
                public void run() {
                    StringBuffer alarmNoticeBuilder = new StringBuffer(notice);
                    String alarmNotice = notice;
                    //增加插件连接信息
                    try {
                        alarmNoticeBuilder.append(System.lineSeparator()).append("消费源:").append(dataConsumer.getClientInfo()).append(System.lineSeparator())
                                .append("目标端:").append(dataLoader.getClientInfo());
                        alarmNotice = alarmNoticeBuilder.toString();
                    } catch (Throwable e) {
                        LOGGER.error("拼接任务异常停止告警信息出错", e);
                    }
                    try {
                        ClusterProviderProxy.INSTANCE.broadcast(new TaskStoppedByErrorCommand(taskId, dataConsumer.getSwimlaneId(), alarmNotice));
                    } catch (Throwable e) {
                        LOGGER.error("在集群策略存储引擎标识任务因错误失败出错:{}", e.getMessage());
                    }
                    try {
                        //停止任务
                        NodeContext.INSTANCE.getBean(TaskController.class).stopTask(taskId, dataConsumer.getSwimlaneId());
                    } catch (Throwable e) {
                        LOGGER.error("停止任务失败", e);
                    }
                    try {
                        //调整节点健康级别
                        NodeContext.INSTANCE.markTaskError(taskId, alarmNotice);
                    } catch (Throwable e) {
                        LOGGER.warn("调整节点健康级别失败", e);
                    }

                    try {
                        LOGGER.info("开始发送日志通知.....");

                        //上传日志
                        NodeLog log = new NodeLog(NodeLog.LogType.TASK_ALARM, taskId, dataConsumer.getSwimlaneId(), alarmNotice);
                        log.setTitle("【告警】【" + MachineUtils.IP_ADDRESS + "】" + taskId + "-" + dataConsumer.getSwimlaneId() + "任务异常停止");
                        NodeLog.upload(log, getReceivers());
                        LOGGER.info("结束发送日志通知.....");
                    } catch (Throwable e) {
                        LOGGER.warn("停止告警发送失败", e);
                    }
                }
            }.start();
        }
    }

    public DataConsumer getDataConsumer() {
        return dataConsumer;
    }

    public DataLoader getDataLoader() {
        return dataLoader;
    }

    public List<AlertReceiver> getReceivers() {
        return receivers;
    }


    /**
     * 当前任务是否触发
     */
    public boolean triggerStopped() {
        return stopTrigger.get();
    }
}
