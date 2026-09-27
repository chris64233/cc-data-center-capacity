# cc-data-center-capacity

数据中心机柜、供电和制冷资源管理服务：设备上架时在机柜空间、双路电力和制冷容量三个维度上
进行原子预留与交付，支持预留整体迁移和设备下架释放。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 领域模型

- **制冷区域（CoolingZone）**：总制冷容量（按散热量瓦特计），带乐观版本号。
- **机柜（Rack）**：总机位数（U，编号 1..N）、A/B 两路电力容量、所属制冷区域，带乐观版本号。
- **设备申请（DeviceRequest）**：业务申请号 `requestId`（幂等键）、机位高度、主/备电力需求、
  散热量、允许上架的机柜范围，带乐观版本号；状态：PENDING → APPROVED → DECOMMISSIONED。
- **预留（Reservation）**：一次生效的占用记录——连续机位段、主路所在回路（备路在另一回路）、
  双路电力与散热量；状态：ACTIVE / RELEASED。
- **变更事件（ChangeEvent）**：配置变更、提交、批准、迁移、下架的审计记录。

## 主要业务规则

1. **原子预留**：批准申请时在单个事务内一次性预留机位、A/B 两路电力和制冷区域容量；
   任一资源不足即整体拒绝（422），不产生任何部分预留，申请保持 PENDING 可重试。
2. **主备分路**：设备主、备电源永远分配到不同供电回路（主 A 备 B 或主 B 备 A），
   两路容量分别校验，任一路超限即不可放置。
3. **幂等**：
   - `requestId` 是幂等键：同内容重复提交返回原申请（200），同号不同内容返回 409；
   - 已批准的申请重复批准返回当前预留；
   - 重复下架返回首次下架的结果（下架时间、释放的预留 ID 完全一致）。
4. **并发与版本控制**：
   - 资源预留/释放按「制冷区域（ID 升序）→ 机柜（ID 升序）」的固定顺序加悲观写锁，
     并发争抢同一机位或容量时成功结果不会超过任一资源上限；
   - 申请内容变更（PUT）会使申请版本号递增，批准必须携带当前 `expectedVersion`，
     基于旧版本的批准返回 409；
   - 机柜容量配置变更会使机柜版本号递增，批准可通过 `expectedRackVersions`
     声明所基于的机柜配置版本，配置已变化则批准整体失败（409）；
   - 容量配置不允许缩减到低于当前已预留量（422）。
5. **整体迁移**：交付前可将预留整体迁移到目标机柜。新位置的机位、双路电力与制冷容量
   全部锁定成功后才创建新预留并释放旧位置；任一步失败即回滚，原预留保持不变。
   同一制冷区域内迁移不重复占用区域制冷容量。
6. **下架释放**：设备下架一次性释放机位、双路电力与制冷容量，预留记录标记 RELEASED
   并保留审计轨迹。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/zones` | 创建制冷区域 |
| GET/PATCH | `/api/zones/{id}` | 区域容量查询 / 配置变更 |
| POST | `/api/racks` | 创建机柜 |
| GET/PATCH | `/api/racks/{id}` | 机柜资源查询（机位/双路电力占用）/ 配置变更 |
| POST | `/api/requests` | 提交设备申请（按 `requestId` 幂等） |
| GET/PUT | `/api/requests/{requestId}` | 申请查询 / 内容变更（递增版本） |
| POST | `/api/requests/{requestId}/approve` | 批准并原子预留（携带 `expectedVersion`、可选 `expectedRackVersions`） |
| POST | `/api/requests/{requestId}/migrate` | 整体迁移预留到目标机柜 |
| POST | `/api/requests/{requestId}/decommission` | 设备下架，一次性释放全部资源（幂等） |
| GET | `/api/events?requestId=&rackId=&zoneId=` | 变更事件查询 |

错误响应统一为 `{"status": ..., "error": ..., "message": ...}`；404 不存在、
409 状态/版本冲突、422 容量不足或业务规则不满足。

## 测试

`CapacityServiceTest` 覆盖原子预留、主备分路、整体拒绝、幂等提交/批准/下架、
申请与机柜版本冲突、迁移成功与失败回滚、并发争抢不超上限等场景；
`ApiFlowTest` 通过 MockMvc 走通完整 REST 生命周期。
