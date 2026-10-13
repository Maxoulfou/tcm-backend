package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.http.UserAgentUtils;

class TokenServiceTest
{
    private TokenService configuredService(RedisCache cache)
    {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "expireTime", 1440);
        return service;
    }

    @Test
    void refreshedSessionHasTwentyFourHourRedisTtlAndMatchingExpiry()
    {
        RedisCache cache = mock(RedisCache.class);
        TokenService service = configuredService(cache);
        LoginUser user = new LoginUser();
        user.setToken("acceptance-test-session");
        long before = System.currentTimeMillis();

        service.refreshToken(user);

        assertTrue(user.getLoginTime() >= before);
        assertEquals(TimeUnit.HOURS.toMillis(24), user.getExpireTime() - user.getLoginTime());
        verify(cache).setCacheObject(eq("login_tokens:acceptance-test-session"), eq(user), eq(1440), eq(TimeUnit.MINUTES));
    }

    @Test
    void activeSessionOutsideRenewalWindowKeepsItsExistingExpiry()
    {
        RedisCache cache = mock(RedisCache.class);
        TokenService service = configuredService(cache);
        LoginUser user = new LoginUser();
        long expiry = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(21);
        user.setExpireTime(expiry);

        service.verifyToken(user);

        assertEquals(expiry, user.getExpireTime());
        verifyNoInteractions(cache);
    }

    @Test
    void activeSessionInsideRenewalWindowExtendsForAnotherTwentyFourHours()
    {
        RedisCache cache = mock(RedisCache.class);
        TokenService service = configuredService(cache);
        LoginUser user = new LoginUser();
        user.setToken("acceptance-test-session");
        user.setExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(19));

        service.verifyToken(user);

        assertEquals(TimeUnit.HOURS.toMillis(24), user.getExpireTime() - user.getLoginTime());
        verify(cache).setCacheObject(eq("login_tokens:acceptance-test-session"), eq(user), eq(1440), eq(TimeUnit.MINUTES));
    }

    @AfterEach
    void tearDown()
    {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void setUserAgent_shouldPopulateBrowserAndOperatingSystemFromSingleResolvedInfo()
    {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        TokenService tokenService = new TokenService();
        LoginUser loginUser = new LoginUser();
        tokenService.setUserAgent(loginUser);

        assertEquals("内网IP", loginUser.getLoginLocation());
        assertEquals(UserAgentUtils.getBrowser(request.getHeader("User-Agent")), loginUser.getBrowser());
        assertEquals(UserAgentUtils.getOperatingSystem(request.getHeader("User-Agent")), loginUser.getOs());
    }
}
