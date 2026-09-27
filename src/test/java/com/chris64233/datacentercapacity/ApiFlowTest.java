package com.chris64233.datacentercapacity;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端 API 流程：建区域/机柜 → 提交（幂等）→ 批准 → 查询 → 下架（幂等）。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    @Autowired
    MockMvc mvc;

    @Test
    void fullReservationLifecycle() throws Exception {
        // 制冷区域
        MvcResult zoneResult = mvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-API\",\"coolingCapacityW\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableCoolingW").value(10000))
                .andReturn();
        long zoneId = ((Number) JsonPath.read(zoneResult.getResponse().getContentAsString(), "$.id")).longValue();

        // 机柜
        MvcResult rackResult = mvc.perform(post("/api/racks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"R-API\",\"totalUnits\":10,"
                                + "\"feedACapacityW\":2000,\"feedBCapacityW\":2000,"
                                + "\"zoneId\":" + zoneId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.freeUnits").value(10))
                .andReturn();
        long rackId = ((Number) JsonPath.read(rackResult.getResponse().getContentAsString(), "$.id")).longValue();

        String requestBody = "{\"requestId\":\"REQ-API-1\",\"deviceName\":\"gpu-server\","
                + "\"uHeight\":2,\"primaryPowerW\":500,\"backupPowerW\":400,"
                + "\"heatLoadW\":300,\"allowedRackIds\":[" + rackId + "]}";

        // 提交：首次 201，同内容重复 200（幂等），同号不同内容 409
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk());
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody.replace("\"uHeight\":2", "\"uHeight\":4")))
                .andExpect(status().isConflict());

        // 基于旧版本批准 → 409；基于当前版本批准 → 200
        mvc.perform(post("/api/requests/REQ-API-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/requests/REQ-API-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reservation.rackId").value(rackId))
                .andExpect(jsonPath("$.reservation.primaryFeed").value("A"))
                .andExpect(jsonPath("$.reservation.backupFeed").value("B"));

        // 资源查询：机柜、区域、事件
        mvc.perform(get("/api/racks/" + rackId))
                .andExpect(jsonPath("$.usedUnits").value(2))
                .andExpect(jsonPath("$.feedAUsedW").value(500))
                .andExpect(jsonPath("$.feedBUsedW").value(400))
                .andExpect(jsonPath("$.occupiedRanges", hasSize(1)));
        mvc.perform(get("/api/zones/" + zoneId))
                .andExpect(jsonPath("$.usedCoolingW").value(300));
        mvc.perform(get("/api/requests/REQ-API-1"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/events").param("requestId", "REQ-API-1"))
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));

        // 下架：一次性释放全部资源；重复下架返回原结果
        MvcResult first = mvc.perform(post("/api/requests/REQ-API-1/decommission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMMISSIONED"))
                .andReturn();
        MvcResult second = mvc.perform(post("/api/requests/REQ-API-1/decommission"))
                .andExpect(status().isOk())
                .andReturn();
        String decommissionedAt1 = JsonPath.read(first.getResponse().getContentAsString(), "$.decommissionedAt");
        String decommissionedAt2 = JsonPath.read(second.getResponse().getContentAsString(), "$.decommissionedAt");
        org.assertj.core.api.Assertions.assertThat(decommissionedAt2).isEqualTo(decommissionedAt1);

        mvc.perform(get("/api/racks/" + rackId))
                .andExpect(jsonPath("$.usedUnits").value(0))
                .andExpect(jsonPath("$.feedAUsedW").value(0));
        mvc.perform(get("/api/zones/" + zoneId))
                .andExpect(jsonPath("$.usedCoolingW").value(0));
    }

    @Test
    void insufficientCapacityReturns422() throws Exception {
        MvcResult zoneResult = mvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Z-SMALL\",\"coolingCapacityW\":100}"))
                .andExpect(status().isCreated()).andReturn();
        long zoneId = ((Number) JsonPath.read(zoneResult.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult rackResult = mvc.perform(post("/api/racks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"R-SMALL\",\"totalUnits\":10,"
                                + "\"feedACapacityW\":2000,\"feedBCapacityW\":2000,"
                                + "\"zoneId\":" + zoneId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long rackId = ((Number) JsonPath.read(rackResult.getResponse().getContentAsString(), "$.id")).longValue();

        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-TOO-HOT\",\"uHeight\":2,"
                                + "\"primaryPowerW\":500,\"backupPowerW\":400,"
                                + "\"heatLoadW\":300,\"allowedRackIds\":[" + rackId + "]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/requests/REQ-TOO-HOT/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.message").exists());
        // 整体拒绝：未产生任何预留
        mvc.perform(get("/api/racks/" + rackId))
                .andExpect(jsonPath("$.usedUnits").value(0));
    }
}
