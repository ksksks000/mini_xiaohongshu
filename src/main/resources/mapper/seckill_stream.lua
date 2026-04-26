-- ==========================================
-- 业务场景：秒杀/优惠券下单
-- Key 前缀定义：
-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARG[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
-- 2. 判断库存是否充足
-- 逻辑：获取库存 Key 的值，转为数字，判断是否 <= 0
if tonumber(redis.call('GET', stockKey)) <= 0 then
    -- 场景：库存不足
    return 1
end

-- 3. 判断用户是否已下单
-- 逻辑：在订单集合 Key 中查找用户 ID
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    -- 场景：用户已存在，属于重复下单
    return 2
end

-- 4. 执行核心业务逻辑
-- 动作 A：扣减库存 (DECR 命令)
redis.call('DECR', stockKey)

-- 动作 B：记录用户 ID 到 Set 集合 (SADD 命令)
redis.call('SADD', orderKey,  userId)

-- 动作 C：发送订单到stream消息队列
redis.call('xadd','stream.orders','*','userId',userId, 'voucherId',voucherId, 'id',orderId)

-- 5. 返回成功状态
return 0