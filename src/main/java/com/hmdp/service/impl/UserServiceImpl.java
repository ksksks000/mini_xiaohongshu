package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;

import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不合法");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保存验证码
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + phone,
                                                code,
                                                LOGIN_CODE_TTL,
                                                TimeUnit.MINUTES);

        //比对验证码
        //这里没做，先假装验证码是比对过的成功了

        //发送验证码
        //log.info("发送验证码成功，验证码：{}",code);
        log.debug("发送验证码成功，验证码：{}",code);
        //返回结果ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //判断手机号格式是否正确
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不合法");
        }
        //校验验证码
        /*Object code = session.getAttribute("code");
        String code1 = loginForm.getCode();
        if(code == null || !code.toString().equals(code1)){
            //验证码不一致
            return Result.fail("未获取验证码或验证码不一致");
        }*/

        String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_USER_KEY + phone);
        String code = loginForm.getCode();
        if (redisCode == null || !code.equals(redisCode)){
            return Result.fail("未获取验证码或验证码不一致");
        }
        //根据手机号查询用户

        //这行代码挺厉害的，因为有extends ServiceImpl<UserMapper, User> ，所以可以直接操作数据库表了
        // 这个query相当于select * from tb_user
        //eq相当于直接在user表中比对phone
        User user = query().eq("phone", phone).one();

        //用户不存在
        if(user == null){
            log.debug("用户不存在，新建一个用户");
            //创建新用户
            user = createUserWithPhone(phone);
        }

        //将用户保存到session
        /*session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/

        //生成随机token保存到redis作为key
        String token = UUID.randomUUID().toString();

        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) ->fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);

        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //返回结果
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
            return user;
    }

    @Override
    public Result sign() {
            // 1. 获取当前登录用户
            Long userId = UserHolder.getUser().getId();

            // 2. 获取当前日期，用于构建 Redis Key 和 Offset
            // 格式化为 "yyyyMM"，例如 "202310"
            String keySuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

            // 获取今天是几号，例如 10月5日 -> 5
            int dayOfMonth = LocalDateTime.now().getDayOfMonth();

            // 3. 构建 Redis Key: sign:uid:userId:yyyyMM
            String key = USER_SIGN_KEY + userId +  keySuffix;

            // 4. 执行签到
            // setBit(key, offset, true)
            // offset 从 0 开始，所以是 dayOfMonth - 1
            stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
            return Result.ok();

    }


    // 新增：获取连续签到天数
    public Result signCount() {
        // 1. 获取用户和 Key
        Long userId = UserHolder.getUser().getId();
        String keySuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        // 2. 获取今天是几号
        int dayOfMonth = LocalDateTime.now().getDayOfMonth();

        // 3. 使用 bitField 获取从今天往前推的所有签到记录
        // 参数解释：GET u[dayOfMonth] 0
        // u[dayOfMonth]: 获取无符号整数，位宽为当前日期的天数（例如 5 号就取 5 位）
        // 0: 从 offset 0 (也就是 1 号) 开始取
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        // 4. 解析结果
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        // 5. 循环判断二进制末尾是否为 1
        // 只要 num & 1 的结果是 1，说明今天签到了，计数器 +1，然后 num 右移一位（看昨天）
        // 一旦 num & 1 的结果是 0，说明断签了，跳出循环
        while ((num & 1) != 0) {
            count++;
            // 无符号右移，丢弃最低位
            num >>>= 1;
        }

        return Result.ok(count);
    }
}
