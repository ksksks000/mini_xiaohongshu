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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        /*旧的session拦截方法
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");

        if (user==null){
            response.setStatus(401);
            return false;
        }

        UserHolder.saveUser((UserDTO) user);
        return true;*/

        //判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null){
            //没有，需要拦截，并设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //有用户，放行
        return true;

    }

}
