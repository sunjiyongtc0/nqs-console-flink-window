package com.eystar.console.sink;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.eystar.common.cache.redis.util.RedisUtils;
import com.eystar.common.util.UUIDKit;
import com.eystar.common.util.XxlConfBean;
import com.eystar.console.env.BeanFactory;
import com.eystar.console.handler.message.GwInfoMessage;
import com.eystar.console.handler.thread.ProbeInfoThread;
import com.eystar.console.util.InfoLoader;
import com.eystar.gen.entity.CPPinfo;
import com.eystar.gen.entity.CPPinfoReal;
import com.eystar.gen.service.*;
import com.eystar.gen.service.impl.*;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Date;


public class ProbeClickHouseSink extends RichSinkFunction<GwInfoMessage> {

    private final static Logger logger = LoggerFactory.getLogger(ProbeClickHouseSink.class);


    private RedisUtils redisUtils;
    private PInfoService pInfoService;
    private PInfoRealService pInfoRealService;
    private ProbeService probeService;
    private XxlConfBean xxlConfBean;


    protected ApplicationContext beanFactory;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        ExecutionConfig.GlobalJobParameters globalJobParameters = getRuntimeContext()
                .getExecutionConfig().getGlobalJobParameters();
        beanFactory = BeanFactory.getBeanFactory((Configuration) globalJobParameters);
        redisUtils = beanFactory.getBean(RedisUtils.class);
        probeService = beanFactory.getBean(ProbeServiceImpl.class);
        pInfoService = beanFactory.getBean(PInfoServiceImpl.class);
        pInfoRealService = beanFactory.getBean(PInfoRealServiceImpl.class);
        xxlConfBean= beanFactory.getBean(XxlConfBean.class);
        //????????????????????????
        InfoLoader.init(redisUtils,probeService);
        ProbeInfoThread.init(redisUtils,probeService);
        xxlConfBean.init();
    }

    @Override
    public void invoke(GwInfoMessage message, Context context) throws Exception {
        try {
            if (message.getTestTime() == 0) {
                Long time = message.getMsgJson().getLongValue("time"); // ????????????????????????????????????
                // ???????????????3????????????????????????????????????????????????????????????????????????????????????????????????????????????
                if (Math.abs(System.currentTimeMillis() / 1000 - time) > xxlConfBean.getXxlValueByLong("gw-console.probe.time.offset")) {
                    time = System.currentTimeMillis() / 1000;
                }
                message.setTestTime(time);
            }


            String probeId = message.getProbeId();
            long time = message.getTestTime();
            // ?????? ?????????????????????PON????????????WAN????????????probe???
            JSONObject gwInfoJson = message.getMsgJson();
            JSONObject probe_info = gwInfoJson.getJSONObject("probe_info"); // ??????????????????????????????
            JSONObject status_info = gwInfoJson.getJSONObject("status_info"); // ??????????????????
            JSONArray access_type_info = gwInfoJson.getJSONArray("access_type_info");
            JSONArray traffic_info = gwInfoJson.getJSONArray("traffic_info");
            JSONObject sgw_info = gwInfoJson.getJSONObject("sgw_info");
            System.out.println("??????ID = " + probeId + "?????????????????????");
            // ????????????????????????????????????MySQL???????????????????????????
            if (probe_info != null && sgw_info != null && status_info != null) {
                probe_info.put("id", probeId);
                probe_info.put("loid", sgw_info.getString("loid"));
                probe_info.put("pppoe_username", sgw_info.getString("pppoe_username"));
                probe_info.put("ram_rate", status_info.get("ram_rate"));
                probe_info.put("cpu_rate", status_info.get("cpu_rate"));
                ProbeInfoThread.run(probe_info);
            }

            // ???????????????????????????BigData???
            CPPinfo pinfo = new CPPinfo();
            pinfo.setId( UUIDKit.nextShortUUID());
            pinfo.setProbeId(probeId);
            pinfo.setTimesheet(time);
            pinfo.setProbeInfo(probe_info == null ? null : probe_info.toJSONString());
            pinfo.setAccessTypeInfo(access_type_info == null ? null : access_type_info.toJSONString());
            pinfo.setTrafficInfo( traffic_info == null ? null : traffic_info.toJSONString());
            pinfo.setSgwInfo(sgw_info == null ? null : sgw_info.toJSONString());
            pinfo.setStatusInfo(status_info == null ? null : status_info.toJSONString());

            // ????????????????????????
            Date date = new Date(time * 1000);

            long timesheet_d = DateUtil.beginOfDay(date).getTime() / 1000;

            pinfo.setTimesheetH( DateUtil.beginOfDay(date).getTime() / 1000 + DateUtil.hour(date, true) * 3600);
            pinfo.setTimesheetD(timesheet_d);
            pinfo.setTimesheetW( DateUtil.beginOfWeek(date).getTime() / 1000);
            pinfo.setTimesheetM( DateUtil.beginOfMonth(date).getTime() / 1000);

            pinfo.setTimesheetPar( new Date(timesheet_d * 1000));
            pinfo.setCreateTime( System.currentTimeMillis() / 1000);

            try {
//                pInfoService.insert(pinfo);
                CPPinfoReal pinfoReal = new CPPinfoReal();
                pinfoReal.setId( pinfo.getId());
                pinfoReal.setProbeId(probeId);
                pinfoReal.setTimesheet(time);
                pinfoReal.setProbeInfo(probe_info == null ? null : probe_info.toJSONString());
                pinfoReal.setAccessTypeInfo(access_type_info == null ? null : access_type_info.toJSONString());
                pinfoReal.setTrafficInfo( traffic_info == null ? null : traffic_info.toJSONString());
                pinfoReal.setSgwInfo(sgw_info == null ? null : sgw_info.toJSONString());
                pinfoReal.setStatusInfo(status_info == null ? null : status_info.toJSONString());
                pinfoReal.setCreateTime(pinfo.getCreateTime());
//                pInfoRealService.insert(pinfoReal);
                System.out.println("??????id = " + probeId + ", ??????BigData??????????????????");
            } catch (Exception e) {
                System.out.println("??????id = " + probeId + ", ??????BigData??????????????????"+ e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("?????????????????????????????????????????? = " + message.toString()+ e.getMessage());
        }
    }


    @Override
    public void close() throws Exception {
        super.close();
    }




}
