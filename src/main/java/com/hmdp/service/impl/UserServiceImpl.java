package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Random;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不合法");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保存验证码
        session.setAttribute("code",code);

        //比对验证码
        //这里没做，先假装验证码是比对过的成功了

        //发送验证码
        //log.info("发送验证码成功，验证码：{}",code);
        log.debug("发送验证码成功，验证码：{}",code);
        //返回结果ok
        return Result.ok();
    }
}
