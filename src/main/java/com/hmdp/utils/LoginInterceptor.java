package com.hmdp.utils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        /*HttpSession session = request.getSession();
        Object user = session.getAttribute("user");

        if (user==null){
            response.setStatus(401);
            return false;
        }

        UserHolder.saveUser((UserDTO) user);
        return true;*/

        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);


        if (userMap.isEmpty()){
            response.setStatus(401);
            return false;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        UserHolder.saveUser(userDTO);

        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                                RedisConstants.LOGIN_USER_TTL,
                                TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        UserHolder.removeUser();
    }
}
