package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.constant.FileConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把下载目录挂成静态资源，让 {@code ResourceDownloadTool} 返回的链接真的打得开。
 *
 * <p>没有这个类的话，那个工具的一切都是对的——文件确实下载到了磁盘上，
 * 返回的 URL 也确实拼得工工整整——只是 <b>没人处理这个路径，访问就是 404</b>。
 * 而工具的描述里明确要求模型「把链接放进最终回答」，于是用户会拿到一个点开是 404 的链接。
 * 这种「所有环节都成功，结果不可用」的问题最难发现，所以映射这一层必须补齐。
 *
 * <p>只映射 {@code /files/download/**} 这一个目录，不映射 {@code tmp} 的其它子目录：
 * {@code FileOperationTool} 写的文件也在 {@code tmp} 下，那些不该随便对外暴露。
 * 路径里的文件名在落盘前已经被清洗过（见 {@code ToolFileNames}），
 * 不含路径分隔符，因此不存在 {@code ../} 绕出去读别的文件的问题。
 */
@Slf4j
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    /** 对外的访问前缀，要和 {@code ResourceDownloadTool} 拼出来的地址一致。 */
    private static final String DOWNLOAD_URL_PATTERN = "/files/download/**";

    /**
     * 头像的访问前缀，要和 {@code AvatarStorage.URL_PREFIX} 一致。
     *
     * <p>它和下载目录是<b>两个分开的映射</b>，而不是把头像也放进下载目录：
     * 那个目录是「工具抓回来的东西」，会被清理；头像不该跟着一起消失。
     */
    private static final String AVATAR_URL_PATTERN = "/files/avatar/**";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        mapDirectory(registry, DOWNLOAD_URL_PATTERN, FileConstant.DOWNLOAD_DIR, "下载工具的链接指向这里");
        mapDirectory(registry, AVATAR_URL_PATTERN, FileConstant.AVATAR_DIR, "用户头像指向这里");
    }

    /** 把一个本地目录挂到一个 URL 前缀上，两个映射的写法保持一致。 */
    private static void mapDirectory(ResourceHandlerRegistry registry, String pattern,
                                     String directory, String note) {
        // 用 toUri() 而不是拼 "file:" + 路径：Windows 上路径分隔符是反斜杠，
        // 直接拼出来的是 file:D:\...\download，Spring 解析不了。
        // 目录还不存在时 toUri() 不带结尾斜杠，而资源位置少了它就匹配不到子路径，所以补上
        String location = Paths.get(directory).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(pattern).addResourceLocations(location);
        log.info("[StaticResourceConfig] 已把 {} 映射到 {}（{}）",
                pattern, Path.of(directory).toAbsolutePath(), note);
    }

    /**
     * 前端是 history 模式的单页应用：{@code /slim}、{@code /manus}、{@code /knowledge}
     * 这些地址由 Vue Router 在浏览器里接管，服务端并没有对应的资源。
     * 用户在那些页面上按一次 F5，请求就会直接打到后端——没有下面这两条转发就是 404，
     * 而「点着没问题、一刷新就白屏」是很难往这个方向想的。
     *
     * <h2>这条规则必须显式排除每一个静态资源前缀，不能靠「路径里有不带点的段」蒙混</h2>
     *
     * <p><b>这里踩过一个坑，别再踩回去</b>：最初的写法是
     * {@code /{path:[^\.]*}} 和 {@code /{path:[^\.]*}/**}，以为「路径里有点就不是前端路由」
     * 能把静态文件挡在外面。错在 {@code [^\.]*} 只约束了<b>第一个路径段</b>——
     * {@code /assets/index-abc.js} 的第一段是 {@code assets}（没有点），
     * 点在第二段，于是整条被匹配上，转发去了 index.html。
     *
     * <p>后果不是「少一个文件」，而是<b>整站白屏</b>：视图控制器的优先级高于静态资源
     * （{@code ViewControllerHandlerMapping} 的 order 是 1，资源处理器排在最后），
     * 所以构建产物里那个 {@code /assets/*.js} 永远轮不到，浏览器拿到的是
     * {@code Content-Type: text/html} 的 index.html，拒绝把它当 module 执行。
     * 只在「构建后由 Spring Boot 托管」这一种部署下出现，Vite dev server 完全正常——
     * 而这恰恰是最容易漏测的那一种。
     *
     * <p>所以下面把 {@code assets} 和 {@code api}、{@code files} 并排列出。
     * <b>将来 Vite 若多出一个顶层产物目录，必须同步加到这里。</b>
     *
     * <p>{@code api} 和 {@code files} 其实优先级上本来就轮不到视图控制器
     * （{@code @RequestMapping} 的 order 是 0，比 1 小），写出来是为了
     * 「即使哪天优先级变了也不会把接口吞掉」——一条宽规则悄悄盖住接口的表现是
     * 接口全部 404，而排查时没人会先怀疑静态资源配置。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController(SINGLE_SEGMENT)
                .setViewName("forward:/index.html");
        registry.addViewController(SINGLE_SEGMENT + "/**")
                .setViewName("forward:/index.html");
    }

    /**
     * 匹配「一个不以点结尾、且不是静态资源前缀的路径段」。
     *
     * <p>不以点结尾这一条挡的是 {@code /index.html}、{@code /favicon.ico} 这类真实文件；
     * 后面那几个排除项挡的是目录（见上面方法的注释）。
     *
     * <p>包级可见而不是 private，是为了让 {@code StaticResourceConfigTest} 直接引用它——
     * 这条规则最容易改错的地方就是「哪个前缀被排除了」，而那种错误在测试里复制一份字面量
     * 就永远测不出来。
     */
    static final String SINGLE_SEGMENT = "/{path:^(?!api$|assets$|files$)[^\\.]*}";
}
