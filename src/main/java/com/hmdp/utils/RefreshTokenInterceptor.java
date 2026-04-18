package com.hmdp.utils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {


    //TODO 状态刷新拦截器，只做刷新，判断身份验证交给下一层拦截器
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {


        //从请求头中获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            //就算没有用户也不拦截，直接放行给下一层拦截器
            //因为这一层拦截器的作用本身就是拿来刷新token时长状态的，
            // 你本身都没登录没token，那我还刷新个屁，交给下一层拦截好了
            return true;
        }

        //有用户，把用户的token作为key值传入进去hash表
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);


        //判断hash表（实际是key值）是否为空，即是否真的有用户进来了，
        // 没有登录？也交给下一层做拦截
        if (userMap.isEmpty()){
            return true;
        }
        //把用户信息以DTO的形式存入到value里面
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        UserHolder.saveUser(userDTO);

        //刷新状态
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                                RedisConstants.LOGIN_USER_TTL,
                                TimeUnit.MINUTES);
        return true;
    }

    //结束登录状态
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        UserHolder.removeUser();
    }
}
