package com.chris64233.datacentercapacity;

import com.chris64233.datacentercapacity.repository.ChangeEventRepository;
import com.chris64233.datacentercapacity.repository.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repository.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repository.RackRepository;
import com.chris64233.datacentercapacity.repository.ReservationRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过 HTTP 验证主要业务规则与错误码。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ChangeEventRepository eventRepo;
    @Autowired
    ReservationRepository reservationRepo;
    @Autowired
    DeviceRequestRepository requestRepo;
    @Autowired
    RackRepository rackRepo;
    @Autowired
    CoolingZoneRepository zoneRepo;

    @BeforeEach
    void clean() {
        eventRepo.deleteAll();
        reservationRepo.deleteAll();
        requestRepo.deleteAll();
        rackRepo.deleteAll();
        zoneRepo.deleteAll();
    }

    private JsonNode postJson(String url, Object body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "" : json.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void endToEndReservationLifecycle() throws Exception {
        JsonNode zone = postJson("/api/zones", Map.of("name", "Z1", "totalCoolingW", 5000), 201);
        long zoneId = zone.get("id").longValue();
        JsonNode rack = postJson("/api/racks", Map.of("name", "R1", "totalU", 42,
                "feedACapacityW", 3000, "feedBCapacityW", 3000, "zoneId", zoneId), 201);
        long rackId = rack.get("id").longValue();

        // 提交申请（幂等：重复提交返回同一申请）
        Map<String, Object> reqBody = Map.of("requestNo", "REQ-1", "uHeight", 2,
                "primaryPowerW", 400, "backupPowerW", 200, "heatLoadW", 300,
                "allowedRackIds", List.of(rackId));
        JsonNode req1 = postJson("/api/requests", reqBody, 201);
        JsonNode req2 = postJson("/api/requests", reqBody, 201);
        assertThat(req2.get("id").longValue()).isEqualTo(req1.get("id").longValue());

        // 修改申请内容使版本递增，基于旧版本的批准 → 409 STALE_VERSION
        long reqVersion = req1.get("version").longValue();
        mvc.perform(put("/api/requests/REQ-1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", reqVersion,
                                "uHeight", 2, "primaryPowerW", 400, "backupPowerW", 200,
                                "heatLoadW", 300, "allowedRackIds", List.of(rackId)))))
                .andExpect(status().isOk());
        JsonNode stale = postJson("/api/requests/REQ-1/approve",
                Map.of("expectedRequestVersion", reqVersion), 409);
        assertThat(stale.get("code").asText()).isEqualTo("STALE_VERSION");

        // 正确版本批准 → 一次性预留成功
        long currentVersion = getJson("/api/requests/REQ-1").get("version").longValue();
        JsonNode approved = postJson("/api/requests/REQ-1/approve",
                Map.of("expectedRequestVersion", currentVersion), 200);
        long reservationId = approved.get("id").longValue();
        assertThat(approved.get("status").asText()).isEqualTo("RESERVED");

        // 机柜与区域资源被占用
        assertThat(getJson("/api/racks/" + rackId).get("usedFeedAW").longValue()).isEqualTo(400);
        assertThat(getJson("/api/zones/" + zoneId).get("usedCoolingW").longValue()).isEqualTo(300);

        // 交付后不可迁移
        postJson("/api/reservations/" + reservationId + "/deliver", null, 200);
        JsonNode migrate = postJson("/api/reservations/" + reservationId + "/migrate",
                Map.of("targetRackId", rackId, "uStart", 10), 409);
        assertThat(migrate.get("code").asText()).isEqualTo("INVALID_STATE");

        // 下架一次性释放，重复下架返回原结果
        JsonNode d1 = postJson("/api/reservations/" + reservationId + "/decommission", null, 200);
        JsonNode d2 = postJson("/api/reservations/" + reservationId + "/decommission", null, 200);
        assertThat(d1.get("status").asText()).isEqualTo("RELEASED");
        assertThat(d2.get("releasedAt").asText()).isEqualTo(d1.get("releasedAt").asText());
        assertThat(getJson("/api/racks/" + rackId).get("usedFeedAW").longValue()).isZero();
        assertThat(getJson("/api/zones/" + zoneId).get("usedCoolingW").longValue()).isZero();

        // 事件查询
        JsonNode events = getJson("/api/events?requestNo=REQ-1");
        assertThat(events.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void insufficientCapacityReturns422() throws Exception {
        JsonNode zone = postJson("/api/zones", Map.of("name", "Z1", "totalCoolingW", 5000), 201);
        JsonNode rack = postJson("/api/racks", Map.of("name", "R1", "totalU", 42,
                "feedACapacityW", 100, "feedBCapacityW", 100, "zoneId", zone.get("id").longValue()), 201);
        postJson("/api/requests", Map.of("requestNo", "REQ-1", "uHeight", 2,
                "primaryPowerW", 400, "backupPowerW", 200, "heatLoadW", 300,
                "allowedRackIds", List.of(rack.get("id").longValue())), 201);

        mvc.perform(post("/api/requests/REQ-1/approve").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedRequestVersion", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));
    }
}
