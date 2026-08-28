package com.traderakshak.config;

// =============================================================================
// WebConfig — TradeRakshak™ v11.5 (No-Docker Edition)
//
// Configures Spring MVC for single-JAR deployment:
//   1. All /api/** requests go to Spring REST controllers
//   2. All other GET requests (Angular routes) serve index.html from static/
//      so Angular router can handle client-side navigation
//   3. Static assets (*.js, *.css, assets/) served directly from classpath
//
// This replaces the Nginx reverse proxy used in the Docker edition.
// =============================================================================

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.method.HandlerTypePredicate;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api",
            HandlerTypePredicate.forAnnotation(RestController.class));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve all static files from /static/ in classpath
        // Angular build output is placed here during build
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new AngularSpaResourceResolver());
    }

    /**
     * SPA Resource Resolver: if a file is not found in /static/,
     * serve index.html instead. This enables Angular client-side routing.
     * e.g. /dashboard, /stock-ranking, /strategy-builder etc.
     * all serve index.html, then Angular router handles the route.
     */
    static class AngularSpaResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = location.createRelative(resourcePath);
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            // Fall back to index.html for Angular SPA routing
            Resource indexHtml = new ClassPathResource("/static/index.html");
            return indexHtml.exists() ? indexHtml : null;
        }
    }
}
