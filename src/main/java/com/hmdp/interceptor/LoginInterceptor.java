package com.hmdp.interceptor;

import cn.hutool.http.HttpStatus;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 2023/1/19 14:55
 *
 * @author tfqy
 */

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
