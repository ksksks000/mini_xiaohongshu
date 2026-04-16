package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Random;

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

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //判断手机号格式是否正确
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不合法");
        }
        //校验验证码
        Object code = session.getAttribute("code");
        String code1 = loginForm.getCode();
        if(code == null || !code.toString().equals(code1)){
            //验证码不一致
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

        //将用户保存到数据库
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //返回结果
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
            return user;
    }
}
