package com.xianyusmart.config;

import com.xianyusmart.interceptor.AuthInterceptor;
import com.xianyusmart.interceptor.AccessControlInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.nio.file.Paths;
import java.util.List;

/**
 * Web MVC 配置
 * 支持 Vue Router 的 History 模式
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private AccessControlInterceptor accessControlInterceptor;

    @Autowired
    private WebProperties webProperties;

    @Value("${app.security.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.media.storage-dir:/app/data/media}")
    private String mediaStorageDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**", "/ai/**")
                .excludePathPatterns("/api/login/**", "/api/system/version");
        registry.addInterceptor(accessControlInterceptor)
                .addPathPatterns("/api/**", "/ai/**")
                .excludePathPatterns("/api/login/**", "/api/system/version");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(Paths.get(mediaStorageDir).toAbsolutePath().normalize().toUri().toString());
        registry.addResourceHandler("/**")
                .addResourceLocations(webProperties.getResources().getStaticLocations())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource resolveResourceInternal(HttpServletRequest request,
                                                               String requestPath,
                                                               List<? extends Resource> locations,
                                                               ResourceResolverChain chain) {
                        Resource exact = super.resolveResourceInternal(request, requestPath, locations, chain);
                        if (exact != null || requestPath.startsWith("api/") || requestPath.startsWith("ai/")) {
                            return exact;
                        }
                        // Only fall back to the SPA shell after every configured
                        // location has been checked for an exact asset.
                        return super.resolveResourceInternal(request, "index.html", locations, chain);
                    }
                });
    }
}
