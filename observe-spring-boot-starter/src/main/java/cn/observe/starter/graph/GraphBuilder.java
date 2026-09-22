package cn.observe.starter.graph;

import cn.observe.core.model.EdgeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 构图器：事件流 → 图。
 *
 * <p>★ 去重天然发生：同一个 {@code node_key} 的事件（前端发起的 API 与后端执行的 API）
 * 自动塌成一个节点 —— 这就是 SCHEMA.md 第三节去重规则要解决的问题，
 * 构图侧不需要任何特殊逻辑。
 *
 * <p>边有两个来源：
 * <ol>
 *   <li>事件自带的 {@code parent_key + edge_type}（NAVIGATE / TRIGGER / CALL）</li>
 *   <li>{@link EdgeType#PRECEDES}：按 step_no 顺序在相邻的不同节点间补齐，
 *       仅当两个节点之间尚无显式边时才补</li>
 * </ol>
 */
public final class GraphBuilder {

    private GraphBuilder() {
    }

    public static Map<String, Object> build(String sessionId, List<Map<String, Object>> rows) {
        Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        // 显式边: "src\u0000dst\u0000type" -> Edge
        Map<String, Edge> edges = new LinkedHashMap<String, Edge>();
        // 已存在的 src->dst（不限类型），用于决定是否补 PRECEDES
        Set<String> explicitPairs = new LinkedHashSet<String>();

        // 用于 PRECEDES 的「去重后的节点序列」
        List<String> sequence = new ArrayList<String>();
        String lastNode = null;

        for (Map<String, Object> row : rows) {
            String nodeKey = str(row.get("node_key"));
            if (nodeKey == null || nodeKey.isEmpty()) {
                continue;
            }
            String layer = str(row.get("layer"));
            String type = str(row.get("event_type"));
            String label = str(row.get("node_label"));

            Node n = nodes.get(nodeKey);
            if (n == null) {
                // ★ 记下节点首次出现的步序：泳道图靠它定位 x 轴（时间/序列）。
                //   行已按 (step_no, occurred_at, id) 排序，所以首个出现的 step 就是最早步序。
                int step = 0;
                Object sv = row.get("step_no");
                if (sv instanceof Number) {
                    step = ((Number) sv).intValue();
                }
                n = new Node(nodeKey, label, layer, type, step);
                nodes.put(nodeKey, n);
            } else {
                if (n.label == null || n.label.isEmpty()) {
                    n.label = label;
                }
                // ★ 层归属用优先级决定，不能用「先到的那条」——那是非确定的。
                //   API 节点同时有前端（发起）与后端（执行）两条事件，
                //   语义上它就是后端端点，所以 BACKEND > RESOURCE > FRONTEND。
                if (layerRank(layer) > layerRank(n.layer)) {
                    n.layer = layer;
                }
            }
            n.count++;
            Object dur = row.get("duration_ms");
            if (dur instanceof Number) {
                n.totalMs += ((Number) dur).longValue();
                n.hasDuration = true;
            }
            String st = str(row.get("status"));
            if ("ERROR".equals(st)) {
                n.errorCount++;
            }

            String parent = str(row.get("parent_key"));
            String edgeType = str(row.get("edge_type"));
            if (parent != null && !parent.isEmpty() && edgeType != null && !edgeType.isEmpty()
                    && !parent.equals(nodeKey)) {
                String k = parent + '\u0000' + nodeKey + '\u0000' + edgeType;
                Edge e = edges.get(k);
                if (e == null) {
                    e = new Edge(parent, nodeKey, edgeType);
                    edges.put(k, e);
                }
                e.weight++;
                explicitPairs.add(parent + '\u0000' + nodeKey);
            }

            // 去重后的相邻序列（用于补 PRECEDES）
            if (!nodeKey.equals(lastNode)) {
                sequence.add(nodeKey);
                lastNode = nodeKey;
            }
        }

        // 补 PRECEDES
        for (int i = 0; i + 1 < sequence.size(); i++) {
            String a = sequence.get(i);
            String b = sequence.get(i + 1);
            if (a.equals(b) || explicitPairs.contains(a + '\u0000' + b)) {
                continue;
            }
            String k = a + '\u0000' + b + '\u0000' + EdgeType.PRECEDES.name();
            if (!edges.containsKey(k)) {
                edges.put(k, new Edge(a, b, EdgeType.PRECEDES.name()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("sessionId", sessionId);
        out.put("eventCount", rows.size());
        out.put("nodeCount", nodes.size());
        out.put("edgeCount", edges.size());

        List<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
        for (Node n : nodes.values()) {
            nodeList.add(n.toMap());
        }
        List<Map<String, Object>> edgeList = new ArrayList<Map<String, Object>>();
        for (Edge e : edges.values()) {
            edgeList.add(e.toMap());
        }
        out.put("nodes", nodeList);
        out.put("edges", edgeList);
        return out;
    }

    // ── 内部结构 ──────────────────────────────────────────────────

    static final class Node {
        final String id;
        String label;
        String layer;
        final String type;
        /** 首次出现的步序（泳道图的 x 轴坐标依据） */
        final int step;
        int count;
        long totalMs;
        boolean hasDuration;
        int errorCount;

        Node(String id, String label, String layer, String type, int step) {
            this.id = id;
            this.label = label;
            this.layer = layer;
            this.type = type;
            this.step = step;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("label", label == null || label.isEmpty() ? id : label);
            m.put("layer", layer);
            m.put("type", type);
            m.put("step", step);
            m.put("count", count);
            m.put("totalMs", hasDuration ? totalMs : null);
            m.put("avgMs", hasDuration && count > 0 ? totalMs / count : null);
            m.put("errorCount", errorCount);
            return m;
        }
    }

    static final class Edge {
        final String source;
        final String target;
        final String type;
        int weight = 1;

        Edge(String source, String target, String type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("source", source);
            m.put("target", target);
            m.put("type", type);
            m.put("weight", weight);
            return m;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 节点层归属优先级：后端 > 资源层 > 前端 */
    private static int layerRank(String layer) {
        if ("BACKEND".equals(layer)) {
            return 3;
        }
        if ("RESOURCE".equals(layer)) {
            return 2;
        }
        return 1;
    }
}
