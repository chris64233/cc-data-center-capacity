# cc-data-center-capacity

数据中心机柜、供电和制冷资源管理服务：设备上架申请在批准时一次性预留机柜机位、双路电力和制冷容量，支持交付前整体迁移、交付与下架释放。

## 领域模型

- **制冷区域（CoolingZone）**：总制冷量（W）与已预留量。
- **机柜（Rack）**：机位总数（U，编号 1..N）、A/B 两路电力容量与已用量、所属制冷区域。
- **设备申请（DeviceRequest）**：机位高度（U）、主/备电力需求（W）、散热量（W）、允许的机柜范围；`requestNo` 为幂等键。
- **预留（Reservation）**：一次批准产生的资源占用——机位区间 `[uStart, uStart+uHeight-1]`、两路各自功率、制冷量；状态 `RESERVED → DELIVERED → RELEASED`。
- **变更事件（ChangeEvent）**：申请创建/修改、预留、迁移、交付、下架、容量配置变更的审计记录。

## 主要业务规则

1. **一次性原子预留**：批准申请时在允许的机柜范围内（按机柜 id 升序）找到第一个同时满足「连续空闲机位 + 双路电力 + 区域制冷」的机柜，在同一事务内预留全部资源；任一资源不足则整体拒绝（422），不产生任何部分占用，申请保持待批准可重试。
2. **主备分路**：主路电源与备路电源永远分配到不同供电回路（A/B）。默认主路优先落 A 路，放不下时自动交换；也可在批准/迁移时指定 `primaryFeed`。
3. **幂等**：相同 `requestNo` 重复提交，内容一致返回已有申请，内容冲突返回 409。下架幂等：重复下架返回首次下架的原结果（相同 `releasedAt`），资源不重复释放。
4. **并发安全**：预留/迁移/下架在事务内对机柜和制冷区域加悲观写锁（`SELECT FOR UPDATE`），锁顺序固定为先机柜（id 升序）后区域（id 升序）。并发争抢同一机位或容量时，成功结果不会超过任一资源上限，也不会死锁。
5. **版本控制**：申请、机柜、区域均带乐观版本号。批准必须携带 `expectedRequestVersion`（指定机柜时可带 `expectedRackVersion`）；申请内容或容量配置变化会使版本递增，基于旧版本的批准失败（409 `STALE_VERSION`）。容量配置修改同样要求 `expectedVersion`，且容量不能缩减到已占用量以下。
6. **整体迁移**：交付前（`RESERVED`）可将预留整体迁移到新机柜/新机位。新位置的机位、双路电力、制冷全部锁定成功后才释放旧位置；任一资源不足则回滚，原预留保持不变。交付后不可迁移。
7. **下架释放**：下架一次性释放机位、双路电力和制冷全部关联资源，释放后的资源可被后续申请复用。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/zones` | 创建制冷区域 |
| GET / PUT | `/api/zones/{id}` | 区域容量查询 / 修改（需 `expectedVersion`） |
| POST | `/api/racks` | 创建机柜 |
| GET / PUT | `/api/racks/{id}` | 机柜资源查询 / 配置修改（需 `expectedVersion`） |
| POST | `/api/requests` | 提交设备申请（`requestNo` 幂等） |
| GET / PUT | `/api/requests/{requestNo}` | 申请查询 / 内容修改（仅待批准，需 `expectedVersion`） |
| POST | `/api/requests/{requestNo}/approve` | 批准并一次性预留（需 `expectedRequestVersion`） |
| GET | `/api/reservations/{id}`、`/api/reservations?requestNo=` | 设备预留查询 |
| POST | `/api/reservations/{id}/migrate` | 交付前整体迁移 |
| POST | `/api/reservations/{id}/deliver` | 交付 |
| POST | `/api/reservations/{id}/decommission` | 下架释放（幂等） |
| GET | `/api/events?requestNo=&type=` | 变更事件查询 |

错误响应统一为 `{"code": ..., "message": ...}`：`404 NOT_FOUND`、`409 STALE_VERSION / REQUEST_NO_CONFLICT / INVALID_STATE / CAPACITY_BELOW_USED`、`422 INSUFFICIENT_CAPACITY`。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1（H2 内存库）

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run
