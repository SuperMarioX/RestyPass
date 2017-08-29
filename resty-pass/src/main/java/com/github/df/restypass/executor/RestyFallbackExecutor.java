package com.github.df.restypass.executor;

import com.github.df.restypass.annotation.RestyService;
import com.github.df.restypass.command.RestyCommand;
import com.github.df.restypass.command.RestyCommandConfig;
import com.github.df.restypass.enums.RestyCommandStatus;
import com.github.df.restypass.exception.execute.RestyException;
import com.github.df.restypass.util.ClassTools;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务降级处理类
 * Created by darrenfu on 17-7-27.
 */
public class RestyFallbackExecutor implements FallbackExecutor {
    private static final Logger log = LoggerFactory.getLogger(RestyFallbackExecutor.class);

    private static ConcurrentHashMap<Class, Object> fallbackClassMap = new ConcurrentHashMap<>();

    @Override
    public boolean executable(RestyCommand restyCommand) {
        if (restyCommand == null) {
            return false;
        }
        RestyCommandConfig commandConfig = restyCommand.getRestyCommandConfig();
        return restyCommand.getStatus() == RestyCommandStatus.FAILED
                && commandConfig.isFallbackEnabled()
                && ((commandConfig.getFallbackClass() != null && commandConfig.getFallbackClass() != RestyService.Noop.class)
                || StringUtils.isNotEmpty(commandConfig.getFallbackBean()));
    }

    @Override
    public Object execute(RestyCommand restyCommand) {

        RestyCommandConfig config = restyCommand.getRestyCommandConfig();
        Class fallbackClass = config.getFallbackClass();

        if (fallbackClass != null && fallbackClass != RestyService.Noop.class) {
            Object fallbackObj = fallbackClassMap.get(fallbackClass);
            if (fallbackObj == null) {
                fallbackObj = ClassTools.instance(fallbackClass);
                Object existObj = fallbackClassMap.putIfAbsent(fallbackClass, fallbackObj);
                if (existObj != null) {
                    fallbackObj = existObj;
                }
            }
            try {
                return findAndInvokeMethodInFallbackClass(fallbackClass, restyCommand, fallbackObj);
            } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
                log.warn("调用降级服务出错:{}", e);
            }

        }
        return null;
    }


    private Object findAndInvokeMethodInFallbackClass(Class fallbackClass,
                                                      RestyCommand restyCommand,
                                                      Object fallbackObj) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Method serviceMethod = restyCommand.getServiceMethod();
        String methodName = serviceMethod.getName();


        Method method = getMethod(fallbackClass, methodName, copyParamsWithException(restyCommand));
        if (method != null) {
            return invokeMethod(method, fallbackObj, copyArgsWithException(restyCommand));
        }
        method = getMethod(fallbackClass, methodName, serviceMethod.getParameterTypes());
        if (method == null) {
            log.error("{}中没有发现合适的降级方法:{}", fallbackClass, methodName);
            throw new NoSuchMethodException(fallbackClass + "没有合适的降级方法:" + methodName);
        }
        return invokeMethod(method, fallbackObj, restyCommand.getArgs());
    }


    private Method getMethod(Class clz, String methodName, Class<?>[] paramTypes) {
        try {
            return clz.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Object invokeMethod(Method method, Object obj, Object[] args) throws InvocationTargetException, IllegalAccessException {
        return method.invoke(obj, args);
    }

    private Class<?>[] copyParamsWithException(RestyCommand restyCommand) {
        Method serviceMethod = restyCommand.getServiceMethod();
        if (serviceMethod.getParameterTypes().length == 0) {
            return new Class[]{RestyException.class};
        }

        Class<?>[] paramTypes = new Class[serviceMethod.getParameterTypes().length + 1];
        paramTypes[0] = RestyException.class;
        System.arraycopy(serviceMethod.getParameterTypes(), 0, paramTypes, 1, serviceMethod.getParameterTypes().length);
        return paramTypes;
    }

    private Object[] copyArgsWithException(RestyCommand restyCommand) {
        if (restyCommand.getArgs() == null || restyCommand.getArgs().length == 0) {
            return new Object[]{restyCommand.getFailException()};
        }

        Object[] args = new Object[restyCommand.getArgs().length + 1];
        args[0] = restyCommand.getFailException();
        System.arraycopy(restyCommand.getArgs(), 0, args, 1, restyCommand.getArgs().length);
        return args;
    }

}
