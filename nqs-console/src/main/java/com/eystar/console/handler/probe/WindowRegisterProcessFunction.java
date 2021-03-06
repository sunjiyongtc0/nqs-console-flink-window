package com.eystar.console.handler.probe;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.eystar.common.cache.redis.util.RedisUtils;
import com.eystar.common.util.*;
import com.eystar.console.env.BeanFactory;
import com.eystar.console.util.InfoLoader;
import com.eystar.console.util.ValKit;
import com.eystar.gen.entity.CPHeartbeat;
import com.eystar.gen.entity.TPProbe;
import com.eystar.gen.service.IpRegionService;
import com.eystar.gen.service.PdcRegionService;
import com.eystar.gen.service.ProbeAccessTypeService;
import com.eystar.gen.service.ProbeService;
import com.eystar.gen.service.impl.IpRegionServiceImpl;
import com.eystar.gen.service.impl.PdcRegionServiceImpl;
import com.eystar.gen.service.impl.ProbeAccessTypeServiceImpl;
import com.eystar.gen.service.impl.ProbeServiceImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eystar.console.handler.message.HeartBeatMessage;
import org.springframework.context.ApplicationContext;

public class WindowRegisterProcessFunction extends ProcessWindowFunction<HeartBeatMessage, List<CPHeartbeat>, Boolean, TimeWindow> {
	private static final long serialVersionUID = -7543689324733148489L;
	private final static Logger logger = LoggerFactory.getLogger(WindowRegisterProcessFunction.class);

	private RedisUtils redisUtils;

	private ProbeService probeService;

	private IpRegionService ipRegionService;

	private PdcRegionService pdcRegionService;

	private XxlConfBean xxlConfBean;

	private ProbeAccessTypeService probeAccessTypeService;

	protected ApplicationContext beanFactory;

	@Override
	public void open(Configuration parameters) throws Exception {
		ExecutionConfig.GlobalJobParameters globalJobParameters = getRuntimeContext()
				.getExecutionConfig().getGlobalJobParameters();
		beanFactory = BeanFactory.getBeanFactory((Configuration) globalJobParameters);
		redisUtils = beanFactory.getBean(RedisUtils.class);
		probeService = beanFactory.getBean(ProbeServiceImpl.class);
		ipRegionService = beanFactory.getBean(IpRegionServiceImpl.class);
		pdcRegionService = beanFactory.getBean(PdcRegionServiceImpl.class);
		probeAccessTypeService = beanFactory.getBean(ProbeAccessTypeServiceImpl.class);
		xxlConfBean= beanFactory.getBean(XxlConfBean.class);

		//????????????????????????
		InfoLoader.init(redisUtils,probeService);
		IPHelper.init(redisUtils,ipRegionService,pdcRegionService);
		RedisModifyHelper.init(redisUtils);

		ProbeAccessTypeHelper.init(redisUtils,probeAccessTypeService);
		xxlConfBean.init();
	}

	@Override
	public void process(Boolean isbadMsg, Context context, Iterable<HeartBeatMessage> elements, Collector<List<CPHeartbeat>> out) throws Exception {

		List<CPHeartbeat> datas = new ArrayList<CPHeartbeat>();

		elements.forEach(message -> {
			try {
				if (message.getTestTime() == 0) {
					Long time = message.getMsgJson().getLongValue("time"); // ????????????????????????????????????
					// ???????????????3????????????????????????????????????????????????????????????????????????????????????????????????????????????
					if (Math.abs(System.currentTimeMillis() / 1000 - time) > xxlConfBean.getXxlValueByLong("gw-console.probe.time.offset")) {
						time = System.currentTimeMillis() / 1000;
					}
					message.setTestTime(time);
				}
				if (message == null) {
					return;
				}
				logger.debug("??????ID = " + message.getProbeId() + "???????????????");
				JSONObject infoObj = message.getInfoJson();
				String probeId = message.getProbeId();
				// ???????????????????????????????????????????????????MySQL
				TPProbe probe = new TPProbe();
				probe.setId(probeId);
				probe.setInternetIp(message.getInternetIp());
				probe.setCreateTime(message.getTestTime());
				probe.setLastRegistTime(message.getTestTime());
				probe.setLastHeartbeatTime(message.getTestTime());

				probe.setSoftVer(infoObj.get("soft_ver") == null ? "" : infoObj.getString("soft_ver"));
				probe.setSoVer(infoObj.get("so_ver") == null ? "" : infoObj.getString("so_ver"));
				probe.setTaskQueueSize(infoObj.get("task_queue_size") == null ? 0 : infoObj.getInteger("task_queue_size"));
				probe.setTaskSize(infoObj.get("task_size") == null ? 0 : infoObj.getInteger("task_size"));


				// ?????????????????????IP?????????????????????
				ProbeHelper.setProbeAreaByIp(probe, message.getInternetIp());
				String probe_alias = StrUtil.isBlank(probe.getProvinceName()) ? "??????" : probe.getProvinceName();
				if (StrUtil.isNotBlank(probe.getCityName())) {
					probe_alias += "-" + probe.getCityName();
				}
				if (StrUtil.isNotBlank(probe.getDistrictName())) {
					probe_alias += "-" + probe.getDistrictName();
				}
				probe_alias += "-??????-" + UUIDKit.nextShortUUID();
				probe.setProbeAlias( probe_alias);

				// ??????MySQL???????????????
				if (probeService.insertProbe(probe)>0) {
					// ??????redis
					JSONObject redisJson = new JSONObject();
					redisJson.put("id", probeId);
					redisJson.put("internet_ip", message.getInternetIp());
					redisJson.put("last_heartbeat_time", message.getTestTime());
					redisJson.put("probe_alias", probe_alias);
					redisJson.put("probe_alias_modified", 0);
					redisJson.put("province_code", probe.getProvinceCode() + "");
					redisJson.put("province_name", probe.getDistrictName());
					redisJson.put("city_code", probe.getCityCode() + "");
					redisJson.put("city_name", probe.getCityName());
					redisJson.put("district_code", probe.getDistrictCode() + "");
					redisJson.put("district_name", probe.getDistrictName());
					redisJson.put("town_code", probe.getTownCode() + "");
					redisJson.put("town_name", probe.getTownName());
					redisJson.put("region_path", probe.getRegionPath());
					RedisModifyHelper.updateProbe(probeId, redisJson.toJSONString());

					// ??????????????????????????????
					JSONObject taskSegJson = new JSONObject();
					taskSegJson.put("probeId", probeId);
					taskSegJson.put("provinceCode", probe.getProvinceCode() + "");
					taskSegJson.put("cityCode", probe.getCityCode() + "");
					taskSegJson.put("districtCode", probe.getDistrictCode() + "");
					redisUtils.lpush(XxlConfBean.getXxlValueByString("gw-keys.redis.queue.default.task"), taskSegJson.toJSONString());
					System.out.println("??????" + probeId + "?????????????????????????????????????????????" + taskSegJson.toJSONString());

					// ???????????????????????????bigdata
					CPHeartbeat probeHeartBeatInfo = new CPHeartbeat();
					probeHeartBeatInfo.setId(UUIDKit.nextShortUUID());
					probeHeartBeatInfo.setProbeId(probeId);
					probeHeartBeatInfo.setHeartbeatTime(message.getTestTime());
					probeHeartBeatInfo.setSoftVer(infoObj.get("soft_ver") == null ? "" : infoObj.getString("soft_ver"));
					probeHeartBeatInfo.setSoVer(infoObj.get("so_ver") == null ? "" : infoObj.getString("so_ver"));
					probeHeartBeatInfo.setTaskQueueSize(infoObj.get("task_queue_size") == null ? 0l : infoObj.getLong("task_queue_size"));
					probeHeartBeatInfo.setTaskSize(infoObj.get("task_size") == null ? 0l : infoObj.getLong("task_size"));
					probeHeartBeatInfo.setInternetIp(ValKit.defStr(message.getInternetIp()));


					// ????????????????????????
					Date date = new Date(message.getTestTime() * 1000);
					long heartbeat_time_d = DateUtil.beginOfDay(date).getTime() / 1000;
					probeHeartBeatInfo.setHeartbeatTimeH(DateUtil.beginOfDay(date).getTime() / 1000 + DateUtil.hour(date, true) * 3600);
					probeHeartBeatInfo.setHeartbeatTimeD(heartbeat_time_d);
					probeHeartBeatInfo.setHeartbeatTimeW(DateUtil.beginOfWeek(date).getTime() / 1000);
					probeHeartBeatInfo.setHeartbeatTimeM(DateUtil.beginOfMonth(date).getTime() / 1000);
					probeHeartBeatInfo.setHeartbeatTimePar(new Date(heartbeat_time_d * 1000));
					probeHeartBeatInfo.setCreateTime(System.currentTimeMillis() / 1000);

					datas.add(probeHeartBeatInfo);
					// ??????????????????dns?????????????????????????????????????????????
					redisUtils.del(Constants.PROBE_ACCESS_AMEND + probeId);
				}else {
					logger.error("??????????????????MySQL????????????");
				}
			} catch (Exception e) {
				logger.error("???????????????????????????Message = " + message, e);
			}
		});
		out.collect(datas);
	}

	@Override
	public void close() throws Exception {
	}
}