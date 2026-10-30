package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 校验响应解析逻辑：数组 / items / 单个对象（随机图）三种常见形态。
 */
class GalleryServiceTest {

    private final GalleryService service = new GalleryService(null, new PhotoBridgeService(null));
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTopLevelArray() throws Exception {
        String json = """
            [
              {"id":"a1","url":"https://cdn/x/1.webp","title":"图1","tags":["acg"]},
              {"id":"a2","url":"https://cdn/x/2.webp","title":"图2","tags":["acg"]}
            ]
            """;
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertEquals(2, rows.size());
        assertEquals("a1", rows.get(0).get("id"));
    }

    @Test
    void parsesItemsWrapper() throws Exception {
        String json = """
            {"items":[
              {"id":"b1","url":"https://cdn/y/1.webp","title":"图1"},
              {"id":"b2","url":"https://cdn/y/2.webp","title":"图2"}
            ],"total":2}
            """;
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertEquals(2, rows.size());
        assertEquals("b2", rows.get(1).get("id"));
    }

    @Test
    void parsesSingleObjectAsRandom() throws Exception {
        String json = """
            {"id":"c1","url":"https://cdn/z/1.webp","title":"随机图","tags":["acg","二次元"]}
            """;
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertEquals(1, rows.size());
        assertEquals("c1", rows.get(0).get("id"));
    }

    @Test
    void findsArrayWhenWrappedInUnknownKey() throws Exception {
        String json = """
            {"code":0,"data":[{"id":"d1","url":"https://cdn/w/1.webp"}]}
            """;
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertEquals(1, rows.size());
        assertEquals("d1", rows.get(0).get("id"));
    }

    @Test
    void returnsEmptyForUnsupportedPayload() throws Exception {
        String json = "{\"message\":\"not found\"}";
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertTrue(rows.isEmpty());
    }

    @Test
    void configUsableOnlyWhenEnabledAndUrlPresent() {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setApiUrl("https://example.com/api/list");
        c.setEnabled(true);
        assertTrue(c.usable());

        c.setEnabled(false);
        assertFalse(c.usable());

        c.setEnabled(true);
        c.setApiUrl("   ");
        assertFalse(c.usable());
    }

    /** 后台 repeater 保存后，配置里的 sources 可能存成数组，也可能存成对象包裹 */
    @Test
    void parsesSourcesFromRepeaterArray() throws Exception {
        String raw = """
            [
              {"name":"派次元","apiUrl":"https://t.paiii.cn/api/list","parser":"auto","enabled":true},
              {"name":"备用源","apiUrl":"https://cdn.example.com/api/list","enabled":false}
            ]
            """;
        List<GallerySourceConfig> list = service.parseSources(raw);
        assertEquals(2, list.size());
        assertEquals("派次元", list.get(0).getName());
        assertTrue(list.get(0).usable());
        assertFalse(list.get(1).usable(), "未勾选启用时不可用");
    }

    @Test
    void parsesSourcesFromObjectWrapper() throws Exception {
        String raw = """
            {"sources":[{"name":"A","apiUrl":"https://a/x"},{"name":"B","apiUrl":"   "}]}
            """;
        List<GallerySourceConfig> list = service.parseSources(raw);
        assertEquals(1, list.size(), "地址为空的源应被丢弃");
        assertEquals("A", list.get(0).getName());
    }

    /** 派次元真实结构：顶层数组，字段为 id/url/title/tags */
    @Test
    void parsesRealPaiiiListPayload() throws Exception {
        String json = """
            [{"id":"img-1","url":"https://img.static.paiii.cn/a.webp","title":"1",
              "tags":["acg","二次元"],"createdAt":"2026-04-28T12:43:03.712Z"}]
            """;
        List<Map<String, Object>> rows = service.extractRows(mapper.readValue(json, Object.class), "auto");
        assertEquals(1, rows.size());
        assertEquals("https://img.static.paiii.cn/a.webp", rows.get(0).get("url"));
        assertEquals(List.of("acg", "二次元"), rows.get(0).get("tags"));
    }

    @Test
    void displayNameFallsBack() {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setApiUrl("https://example.com/x");
        assertEquals("fallback", c.displayNameOrDefault("fallback"));
        c.setName("我的图源");
        assertEquals("我的图源", c.displayNameOrDefault("fallback"));
    }
}
