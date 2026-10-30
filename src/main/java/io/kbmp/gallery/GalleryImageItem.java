package io.kbmp.gallery;

import java.util.List;

/**
 * 一张图片的元数据。
 *
 * <p>注意：这里只保存图片的**地址等元数据**，图片本身始终由第三方 CDN 提供，
 * 不会下载也不会写入 Halo 附件库，因此不占用本地存储。
 */
public class GalleryImageItem {

    private String id;
    private String url;
    private String title;
    private String source;
    private List<String> tags;

    public GalleryImageItem() {
    }

    public GalleryImageItem(String id, String url, String title, String source,
        List<String> tags) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.source = source;
        this.tags = tags;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
