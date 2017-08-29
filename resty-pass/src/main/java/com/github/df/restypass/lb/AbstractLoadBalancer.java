package com.github.df.restypass.lb;

import com.github.df.restypass.command.RestyCommand;
import com.github.df.restypass.lb.server.ServerContext;
import com.github.df.restypass.lb.server.ServerInstance;
import com.github.df.restypass.util.CommonTools;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.github.df.restypass.base.RestyConst.Instance.*;

/**
 * 负载均衡器，顶级抽象类
 * Created by darrenfu on 17-7-31.
 */
public abstract class AbstractLoadBalancer implements LoadBalancer {
    private static Logger log = LoggerFactory.getLogger(AbstractLoadBalancer.class);

    @Override
    public ServerInstance choose(ServerContext context, RestyCommand command, Set<String> excludeInstanceIdSet) {

        if (context == null || command == null || StringUtils.isEmpty(command.getServiceName())) {
            return null;
        }
        List<ServerInstance> serverList = context.getServerList(command.getServiceName());

        if (serverList == null || serverList.size() == 0) {
            return null;
        }


        boolean instancesNotReady = serverList.removeIf(v -> !v.isReady());
        if (instancesNotReady) {
            log.warn("存在状态没有ready的server实例,请调用Ready方法完成ServerInstance初始化");
        }

        if (serverList.size() == 1) {
            if (excludeInstanceIdSet != null
                    && excludeInstanceIdSet.size() > 0
                    && excludeInstanceIdSet.contains(serverList.get(0).getInstanceId())) {
                return null;
            }
            return serverList.get(0);
        }

        if (!CommonTools.isEmpty(excludeInstanceIdSet)) {
            List<ServerInstance> useableServerList = new ArrayList<>();
            for (ServerInstance instance : serverList) {
                if (!excludeInstanceIdSet.contains(instance.getInstanceId())) {
                    useableServerList.add(instance);
                }
            }
            // 排除excludeServer后，有可用server则使用，否则还是使用原始的Server
            if (!CommonTools.isEmpty(useableServerList)) {
                serverList = useableServerList;
            }
        }


        return doChoose(serverList, command);
    }


    /**
     * 基于特定算法，选举可用server实例
     *
     * @param instanceList the instance list
     * @param command      the command
     * @return the server instance
     */
    protected abstract ServerInstance doChoose(List<ServerInstance> instanceList, RestyCommand command);

    /**
     * copy from dubbo
     *
     * @param serverInstance the server instance
     * @return weight
     */
    protected int getWeight(ServerInstance serverInstance) {
        int weight = serverInstance.getPropValue(PROP_WEIGHT_KEY, PROP_WEIGHT_DEFAULT);

        if (weight > 0) {
            long timestamp = getServerStartTime(serverInstance);
            if (timestamp > 0L) {
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                int warmup = serverInstance.getPropValue(PROP_WARMUP_KEY, PROP_WARMUP_DEFAULT);

                if (uptime > 0 && uptime < warmup) {
                    weight = caculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }

        return weight;
    }

    private long getServerStartTime(ServerInstance serverInstance) {
        return serverInstance.getStartTime() != null ? serverInstance.getStartTime().getTime() : serverInstance.getPropValue(PROP_TIMESTAMP_KEY, PROP_TIMESTAMP_DEFAULT);
    }

    /**
     * Caculate warmup weight int.
     *
     * @param uptime the uptime
     * @param warmup the warmup
     * @param weight the weight
     * @return the int
     */
    int caculateWarmupWeight(int uptime, int warmup, int weight) {
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }

}
