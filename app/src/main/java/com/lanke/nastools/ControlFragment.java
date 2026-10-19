package com.lanke.nastools;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 控制页（主页面）：顶部选被控设备，下面读该设备的规则。
 *
 * 待保存语义：模式 / 时段 / 开关的改动只留在内存里，点底部「保存并立即生效」才下发；
 * 下拉刷新或切换设备一律丢弃未保存改动，重新读 NAS 上的真实配置。
 */
public class ControlFragment extends BaseFragment {

    private LinearLayout box;
    private JSONArray devices = new JSONArray();
    private String selId = "";
    private String pendingMode = null;      // null = 未改动
    private JSONObject curDev;              // 当前设备的 NAS 快照
    private JSONObject lastStatus;          // 最近一次 /api/status 的整包（重绘时复用）
    private final List<Win> school = new ArrayList<>();
    private final List<Win> holiday = new ArrayList<>();

    private LinearLayout schoolBox, holidayBox;
    private boolean spinnerGuard = false;

    /** 一个开放时段：起止分钟 + 该时段内外网 / 内网是否放行 */
    private static class Win {
        int s, e;
        boolean wan, lan;

        Win(int s, int e, boolean wan, boolean lan) {
            this.s = s;
            this.e = e;
            this.wan = wan;
            this.lan = lan;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle b) {
        Context c = requireContext();
        box = Ui.vbox(c, 8);
        return makeScroller(c, box);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (box != null && box.getChildCount() == 0) load();
    }

    @Override
    public void load() {
        runAsync(() -> Conn.get(ctx(), "/api/status", null, Api.T_NORMAL), o -> {
            Context c = ctx();
            if (c == null) return;
            box.removeAllViews();
            if (!(o instanceof Api.R) || !((Api.R) o).ok) {
                String msg = (o instanceof Api.R) ? ((Api.R) o).msg() : "未知错误";
                box.addView(Ui.errorBox(c, msg + "\n\n当前设置：" + Prefs.host(c)
                        + ":" + Prefs.sshPort(c)));
                return;
            }
            JSONObject st = ((Api.R) o).json;
            lastStatus = st;
            devices = st.optJSONArray("devices");
            if (devices == null) devices = new JSONArray();

            selId = Prefs.selDev(c);
            JSONObject dev = deviceById(selId);
            if (dev == null && devices.length() > 0) {
                dev = devices.optJSONObject(0);
                selId = dev == null ? "" : dev.optString("id", "");
            }
            pendingMode = null;
            adopt(dev);
            build(c, st);
        });
    }

    private JSONObject deviceById(String id) {
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d != null && id.equals(d.optString("id", ""))) return d;
        }
        return null;
    }

    /** 把设备快照里的规则读进内存（丢弃未保存改动） */
    private void adopt(JSONObject dev) {
        curDev = dev;
        school.clear();
        holiday.clear();
        if (dev == null) return;
        readWins(dev.optJSONArray("school"), school);
        readWins(dev.optJSONArray("holiday"), holiday);
    }

    private void readWins(JSONArray arr, List<Win> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            int s = toMin(w.optString("s", ""));
            int e = toMin(w.optString("e", ""));
            if (s < 0 || e < 0) continue;
            out.add(new Win(s, e, w.optInt("wan", 1) == 1, w.optInt("lan", 1) == 1));
        }
    }

    // ---------------------------------------------------------- 界面

    private void build(Context c, JSONObject st) {
        if (devices.length() == 0) {
            Ui.Card k = Ui.card(c, "还没有被控设备");
            Ui.note(c, k.body, "先到「设备」页添加一台（填名称 + MAC）。\n"
                    + "MAC 在设备本机的 Wi-Fi 设置里能看到，形如 D0:96:EA:0E:00:07。");
            MaterialButton go = Ui.button(c, "去添加设备", true);
            go.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).gotoTab(1);
                }
            });
            k.body.addView(go);
            box.addView(k.view);
            return;
        }
        buildSelector(c, st);
        buildStatus(c, st);
        buildWindows(c);
        buildNetwork(c);
        buildSave(c);
    }

    /** 顶部：选设备 */
    private void buildSelector(Context c, JSONObject st) {
        Ui.Card k = Ui.card(c, "被控设备");
        List<String> names = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            String nm = d == null ? "" : d.optString("name", d.optString("mac", "?"));
            names.add(nm);
            if (d != null && selId.equals(d.optString("id", ""))) idx = i;
        }
        Spinner sp = new Spinner(c);
        sp.setAdapter(new ArrayAdapter<>(c,
                android.R.layout.simple_spinner_dropdown_item, names));
        spinnerGuard = true;
        sp.setSelection(idx);
        spinnerGuard = false;
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (spinnerGuard) return;
                JSONObject d = devices.optJSONObject(pos);
                if (d == null) return;
                String nid = d.optString("id", "");
                if (nid.equals(selId)) return;
                // 换设备 = 丢弃未保存改动，重新读这台设备的规则
                Context cc = ctx();
                if (cc == null) return;
                selId = nid;
                Prefs.setSelDev(cc, nid);
                pendingMode = null;
                adopt(d);
                rebuild(cc, st);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        k.body.addView(sp);
        Ui.row(c, k.body, "今日", st.optString("day_label", "-"));
        box.addView(k.view);
    }

    private void rebuild(Context c, JSONObject st) {
        box.removeAllViews();
        build(c, st);
    }

    /** 状态 + 三个模式键 */
    private void buildStatus(Context c, JSONObject st) {
        Ui.Card k = Ui.card(c, "运行状态");
        JSONObject d = curDev != null ? curDev : new JSONObject();
        Ui.row(c, k.body, "名称", d.optString("name", "-"));
        Ui.row(c, k.body, "MAC", d.optString("mac", "-"));
        String ip = d.optString("ip", "");
        Ui.row(c, k.body, "当前 IP", ip.isEmpty() ? "（ARP 表里没有，设备可能离线）" : ip);

        String stateText = d.optString("state_text", "-");
        boolean wanOk = d.optInt("wan_now", 0) == 1;
        boolean lanOk = d.optInt("lan_now", 0) == 1;
        int col = (wanOk || lanOk) ? Ui.col(c, R.color.ok) : Ui.col(c, R.color.bad);
        Ui.row(c, k.body, "此刻", stateText, col);
        Ui.row(c, k.body, "外网 / 内网",
                (wanOk ? "放行" : "封禁") + " / " + (lanOk ? "放行" : "封禁"), col);

        String p4 = d.isNull("pkts4") ? "-" : String.valueOf(d.optInt("pkts4", 0));
        String p4w = d.isNull("pkts4_wan") ? "-" : String.valueOf(d.optInt("pkts4_wan", 0));
        String p6 = d.isNull("pkts6") ? "-" : String.valueOf(d.optInt("pkts6", 0));
        Ui.row(c, k.body, "已拦截 内网/外网 v4", p4 + " / " + p4w);
        Ui.row(c, k.body, "已拦截 v6", p6);

        String mode = pendingMode != null ? pendingMode : d.optString("mode", "auto");
        LinearLayout r = Ui.buttonRow(c, 3);
        MaterialButton bOpen = Ui.button(c, "长期放行", "open".equals(mode));
        MaterialButton bAuto = Ui.button(c, "校历模式", "auto".equals(mode));
        MaterialButton bBlock = Ui.button(c, "长期封禁", "block".equals(mode));
        bOpen.setOnClickListener(v -> pickMode("open"));
        bAuto.setOnClickListener(v -> pickMode("auto"));
        bBlock.setOnClickListener(v -> pickMode("block"));
        Ui.addToRow(c, r, bOpen);
        Ui.addToRow(c, r, bAuto);
        Ui.addToRow(c, r, bBlock);
        k.body.addView(r);

        if (pendingMode != null) {
            Ui.note(c, k.body, "已选「" + modeName(pendingMode) + "」，还没保存。\n"
                    + "点底部「保存并立即生效」才会下发；刷新或切设备会恢复 NAS 上的当前模式。");
        } else {
            Ui.note(c, k.body, "校历模式 = 按校历的「上学日 / 放假日」自动套用下面两组时段。\n"
                    + "包数一直为 0 说明流量没经过群晖（设备网关没填群晖 IP），不是没封。");
        }
        box.addView(k.view);
    }

    private void pickMode(String m) {
        pendingMode = m;
        Context c = ctx();
        if (c != null && lastStatus != null) rebuild(c, lastStatus);
    }

    private String modeName(String m) {
        if ("open".equals(m)) return "长期放行";
        if ("block".equals(m)) return "长期封禁";
        return "校历模式";
    }

    /** 两组时段：上学日 / 放假日 */
    private void buildWindows(Context c) {
        Ui.Card k1 = Ui.card(c, "上学日开放时段");
        schoolBox = new LinearLayout(c);
        schoolBox.setOrientation(LinearLayout.VERTICAL);
        k1.body.addView(schoolBox);
        renderWins(c, schoolBox, school);
        MaterialButton add1 = Ui.button(c, "+ 添加时段", false);
        add1.setOnClickListener(v -> {
            school.add(new Win(8 * 60, 9 * 60, true, true));
            renderWins(c, schoolBox, school);
        });
        k1.body.addView(add1);
        Ui.note(c, k1.body, "时段之外一律全封（内网 + 外网都断）。\n"
                + "每个时段可以单独决定「外网」和「内网（群晖）」是否放行。");
        box.addView(k1.view);

        Ui.Card k2 = Ui.card(c, "放假日开放时段");
        holidayBox = new LinearLayout(c);
        holidayBox.setOrientation(LinearLayout.VERTICAL);
        k2.body.addView(holidayBox);
        renderWins(c, holidayBox, holiday);
        MaterialButton add2 = Ui.button(c, "+ 添加时段", false);
        add2.setOnClickListener(v -> {
            holiday.add(new Win(18 * 60, 21 * 60, true, true));
            renderWins(c, holidayBox, holiday);
        });
        k2.body.addView(add2);
        Ui.note(c, k2.body, "周末 / 寒暑假 / 法定假日走这一组；今天算哪一组由「校历」页判定。");
        box.addView(k2.view);
    }

    private void renderWins(Context c, LinearLayout container, final List<Win> list) {
        container.removeAllViews();
        if (list.isEmpty()) {
            TextView t = Ui.text(c, "（没有时段 = 全天全封）", 13f, Ui.TEXT2, false);
            t.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
            container.addView(t);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final Win w = list.get(i);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(c, 6), 0, 0);

            MaterialButton bs = Ui.button(c, fmt(w.s), false);
            bs.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bs.setOnClickListener(v -> pickTime(c, list, idx, 0, container));

            TextView arrow = Ui.text(c, "→", 14f, Ui.TEXT2, true);
            arrow.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);

            MaterialButton be = Ui.button(c, fmt(w.e), false);
            be.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            be.setOnClickListener(v -> pickTime(c, list, idx, 1, container));

            MaterialButton del = Ui.button(c, "删除", false);
            del.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            del.setTextColor(0xFFC62828);
            del.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFC62828));
            del.setOnClickListener(v -> {
                list.remove(idx);
                renderWins(c, container, list);
            });

            row.addView(bs);
            row.addView(arrow);
            row.addView(be);
            row.addView(del);
            container.addView(row);

            // 第二行：两个独立开关
            LinearLayout row2 = new LinearLayout(c);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setPadding(0, 0, 0, Ui.dp(c, 4));

            SwitchCompat swW = new SwitchCompat(c);
            swW.setText("外网");
            swW.setTextSize(13f);
            swW.setChecked(w.wan);
            swW.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            swW.setOnCheckedChangeListener((bv, checked) -> w.wan = checked);

            SwitchCompat swL = new SwitchCompat(c);
            swL.setText("内网（群晖）");
            swL.setTextSize(13f);
            swL.setChecked(w.lan);
            swL.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            swL.setOnCheckedChangeListener((bv, checked) -> w.lan = checked);

            row2.addView(swW);
            row2.addView(swL);
            container.addView(row2);
        }
    }

    private void pickTime(Context c, List<Win> list, int idx, int which, LinearLayout container) {
        Win w = list.get(idx);
        int cur = which == 0 ? w.s : w.e;
        new TimePickerDialog(c, (view, h, m) -> {
            if (which == 0) w.s = h * 60 + m;
            else w.e = h * 60 + m;
            renderWins(c, container, list);
        }, cur / 60, cur % 60, true).show();
    }

    /** 全局网络参数入口 */
    private void buildNetwork(Context c) {
        Ui.Card k = Ui.card(c, "网络参数");
        MaterialButton go = Ui.button(c, "打开网络参数", false);
        go.setOnClickListener(v -> startActivity(new Intent(c, NetParamsActivity.class)));
        k.body.addView(go);
        Ui.note(c, k.body, "出网网口、局域网段、群晖 IP 在那一页；一般不用动。\n"
                + "设备 IP / MAC 不在这里配——设备是按 MAC 认的，不用固定 IP。");
        box.addView(k.view);
    }

    private void buildSave(Context c) {
        Ui.Card k = Ui.card(c, "保存");
        MaterialButton save = Ui.button(c, "保存并立即生效", true);
        save.setOnClickListener(v -> saveAll(c));
        k.body.addView(save);
        Ui.note(c, k.body, "只保存当前这台设备（" + (curDev == null ? "-"
                : curDev.optString("name", "-")) + "）。");
        box.addView(k.view);
    }

    // ---------------------------------------------------------- 保存

    private void saveAll(Context c) {
        if (selId.isEmpty()) {
            toast("没有选中设备");
            return;
        }
        for (Win w : school) {
            if (!ok(w)) {
                toast("上学日有一个时段不合法（起止相同 / 超过 12 小时）");
                return;
            }
        }
        for (Win w : holiday) {
            if (!ok(w)) {
                toast("放假日有一个时段不合法（起止相同 / 超过 12 小时）");
                return;
            }
        }

        final String mode = pendingMode != null ? pendingMode
                : (curDev == null ? "auto" : curDev.optString("mode", "auto"));

        runAsync(() -> {
            JSONObject set = new JSONObject();
            set.put("mode", mode);
            set.put("school", winsJson(school));
            set.put("holiday", winsJson(holiday));
            JSONObject body = new JSONObject();
            body.put("op", "update");
            body.put("id", selId);
            body.put("set", set);
            return Conn.post(ctx(), "/api/devices", body, Api.T_LONG);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                toast("已保存并生效");
                JSONObject ap = r.json == null ? null : r.json.optJSONObject("apply");
                showOutput("保存结果", "模式：" + modeName(mode)
                        + "\n上学日 " + school.size() + " 段 / 放假日 " + holiday.size() + " 段"
                        + (ap == null ? "" : "\n\n" + ap.optString("output", "")));
                load();
            } else {
                showOutput("保存失败", r.msg() + (r.json != null ? "\n\n" + r.out() : ""));
            }
        });
    }

    private boolean ok(Win w) {
        if (w.s == w.e) return false;
        int dur = w.e > w.s ? w.e - w.s : (1440 - w.s + w.e);
        return dur <= 12 * 60;
    }

    private JSONArray winsJson(List<Win> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Win w : list) {
            JSONObject o = new JSONObject();
            o.put("s", fmt(w.s));
            o.put("e", fmt(w.e));
            o.put("wan", w.wan ? 1 : 0);
            o.put("lan", w.lan ? 1 : 0);
            arr.put(o);
        }
        return arr;
    }

    // ---------------------------------------------------------- 工具

    private static int toMin(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(int m) {
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }
}
