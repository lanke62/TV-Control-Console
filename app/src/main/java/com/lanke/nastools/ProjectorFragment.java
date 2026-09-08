package com.lanke.nastools;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 电视端功能页：封禁开关 + 开放时段 + 网络参数 + 状态与强制模式，集中在一个页面里 */
public class ProjectorFragment extends BaseFragment {

    private LinearLayout box;

    // 封禁开关（「封禁时踢掉 SMB 会话」已隐藏，固定按默认执行）
    private SwitchCompat swWan, swNas;
    private Spinner spMode;
    // 开放时段
    private LinearLayout schoolBox, holidayBox;
    private final List<int[]> school = new ArrayList<>();
    private final List<int[]> holiday = new ArrayList<>();
    // 待保存的模式（"open"=长期放行 / "auto"=校历模式 / "block"=长期封禁；null=未改动）
    // 页面上所有改动都只存在内存里，点「保存并立即生效」才下发；刷新 / 重开 App 一律恢复 NAS 上的配置
    private String pendingMode = null;
    private JSONObject lastProj;

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
                box.addView(Ui.errorBox(c, msg + "\n\n当前设置：" + Prefs.host(c) + ":" + Prefs.sshPort(c)));
                return;
            }
            JSONObject d = ((Api.R) o).json;
            // 刷新 = 丢弃所有未保存的改动，恢复 NAS 上的配置
            pendingMode = null;
            lastProj = d.optJSONObject("projector");
            school.clear();
            holiday.clear();
            JSONObject win = firstObj(d, "school_windows", "holiday_windows");
            if (win != null) {
                school.addAll(parse(win.optString("school_windows", "")));
                holiday.addAll(parse(win.optString("holiday_windows", "")));
            }
            build(c, d.optJSONObject("projector"));
        });
    }

    /** status 里 windows 可能在 media / calendar / projector 任一个对象下，这里按优先级取第一个 */
    private JSONObject firstObj(JSONObject status, String... keys) {
        if (status == null) return null;
        String[] objs = {"media", "calendar", "projector"};
        for (String o : objs) {
            JSONObject j = status.optJSONObject(o);
            if (j != null) {
                boolean has = false;
                for (String k : keys) if (j.has(k) && !j.isNull(k)) has = true;
                if (has) return j;
            }
        }
        return status.optJSONObject("projector");
    }

    private void build(Context c, JSONObject p) {
        if (p == null) p = new JSONObject();
        buildStatus(c, p);
        buildWindows(c);
        buildSwitches(c, p);
        buildNetwork(c, p);
        buildSave(c);
    }

    // ---------- 状态 + 模式按钮 ----------
    private void buildStatus(Context c, JSONObject p) {
        Ui.Card k = Ui.card(c, "电视端状态");
        if (p.has("error")) {
            Ui.row(c, k.body, "状态", p.optString("error", "读取失败"), Ui.col(c, R.color.bad));
        } else {
            String want = p.optString("want", "");
            boolean blocked = "block".equals(want);
            Ui.row(c, k.body, "应置状态", blocked ? "封禁中" : "放行中",
                    blocked ? Ui.col(c, R.color.bad) : Ui.col(c, R.color.ok));
            Ui.row(c, k.body, "上次状态", p.optString("last", "-"));
            String ovr = p.optString("override", "");
            Ui.row(c, k.body, "当前模式",
                    (ovr.contains("block") ? "长期封禁" : ovr.contains("open") ? "长期放行" : "无（按校历自动）"),
                    ovr.contains("block") ? Ui.col(c, R.color.bad)
                            : ovr.contains("open") ? Ui.col(c, R.color.ok) : Ui.TEXT2);
            Ui.row(c, k.body, "电视端 IP", p.optString("ip", ""));
            // 外网 / 群晖：开 = 放行（不封禁），关 = 封禁（显示的是 NAS 上已保存的状态）
            String wanState = "1".equals(p.optString("block_wan", "1")) ? "关（已封禁）" : "开（已放行）";
            String nasState = "1".equals(p.optString("block_nas", "1")) ? "关（已封禁）" : "开（已放行）";
            Ui.row(c, k.body, "外网 / 群晖", wanState + " / " + nasState, Ui.TEXT2);
            String pk4 = p.isNull("pkts4") ? "-" : String.valueOf(p.optInt("pkts4", 0));
            String pk6 = p.isNull("pkts6") ? "-" : String.valueOf(p.optInt("pkts6", 0));
            Ui.row(c, k.body, "已拦截包数 v4 / v6", pk4 + " / " + pk6);
            Ui.note(c, k.body, "包数是「封禁是否真的生效」的唯一可信证据：\n"
                    + "封禁期间它应该 > 0 且持续上涨；一直是 0 说明流量没经过群晖。");

            // 三个模式按钮同一行：长期放行 / 校历模式 / 长期封禁
            // 只改本地待保存状态，点底部「保存并立即生效」才真正执行
            LinearLayout r = Ui.buttonRow(c, 3);
            MaterialButton bOff = Ui.button(c, "长期放行", "open".equals(pendingMode));
            bOff.setOnClickListener(v -> pickMode("open"));
            MaterialButton bAuto = Ui.button(c, "校历模式", "auto".equals(pendingMode));
            bAuto.setOnClickListener(v -> pickMode("auto"));
            MaterialButton bOn = Ui.button(c, "长期封禁", "block".equals(pendingMode));
            bOn.setOnClickListener(v -> pickMode("block"));
            Ui.addToRow(c, r, bOff);
            Ui.addToRow(c, r, bAuto);
            Ui.addToRow(c, r, bOn);
            k.body.addView(r);
            if (pendingMode != null) {
                String nm = "block".equals(pendingMode) ? "长期封禁"
                        : "open".equals(pendingMode) ? "长期放行" : "校历模式";
                Ui.note(c, k.body, "已选「" + nm + "」，还没保存。点底部「保存并立即生效」才会执行；"
                        + "刷新或重开 App 会恢复 NAS 上的当前模式。");
            }
        }
        box.addView(k.view);
    }

    /** 点模式按钮：只记下待保存状态并重绘页面，不下发 NAS */
    private void pickMode(String m) {
        pendingMode = m;
        Context c = ctx();
        if (c != null && lastProj != null) {
            box.removeAllViews();
            build(c, lastProj);
        }
    }

    // ---------- 独立功能开关 ----------
    // 外网 / 群晖：开 = 放行（不封禁），关 = 封禁。conf 里 1=封禁，0=放行，读写时取反。
    private void buildSwitches(Context c, JSONObject p) {
        Ui.Card k = Ui.card(c, "独立功能开关");
        swWan = switchOf(c, k.body, "外网", !"1".equals(p.optString("block_wan", "1")),
                "开 = 电视端可以上外网；关 = 封禁外网。\n"
                        + "前提：电视端的网关栏必须填群晖 IP，否则流量不过来，关了也封不住。");
        swNas = switchOf(c, k.body, "群晖", !"1".equals(p.optString("block_nas", "1")),
                "开 = 电视端可以访问群晖（SMB / DLNA / 群晖网页）；关 = 封禁访问群晖。\n"
                        + "群晖自己是通信终点，零改造即可拦。");
        // 「封禁时踢掉 SMB 会话」按默认执行（固定 1），不对外提供开关

        TextView lbMode = Ui.text(c, "封禁动作 BLOCK_MODE", 13.5f, Ui.TEXT2, false);
        lbMode.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        k.body.addView(lbMode);
        spMode = spinner(c, k.body, new String[]{"reject", "drop"});
        setSpinner(spMode, p.optString("block_mode", "reject"));
        Ui.note(c, k.body, "reject=优先当场回 RST（群晖内核多数不支持，会自动退回 drop）；\n"
                + "drop=静默丢包，更隐蔽。「立即掐断」靠清 conntrack 实现，与这两者无关。");
        box.addView(k.view);
    }

    // ---------- 开放时段 ----------
    private void buildWindows(Context c) {
        Ui.Card k1 = Ui.card(c, "上学日开放时段");
        schoolBox = new LinearLayout(c);
        schoolBox.setOrientation(LinearLayout.VERTICAL);
        k1.body.addView(schoolBox);
        renderRows(c, schoolBox, school);
        MaterialButton add1 = Ui.button(c, "+ 添加时段", false);
        add1.setOnClickListener(v -> {
            school.add(new int[]{8 * 60, 9 * 60});
            renderRows(c, schoolBox, school);
        });
        k1.body.addView(add1);
        Ui.note(c, k1.body, "这段时间内电视端放行。");
        box.addView(k1.view);

        Ui.Card k2 = Ui.card(c, "放假日开放时段");
        holidayBox = new LinearLayout(c);
        holidayBox.setOrientation(LinearLayout.VERTICAL);
        k2.body.addView(holidayBox);
        renderRows(c, holidayBox, holiday);
        MaterialButton add2 = Ui.button(c, "+ 添加时段", false);
        add2.setOnClickListener(v -> {
            holiday.add(new int[]{18 * 60, 21 * 60});
            renderRows(c, holidayBox, holiday);
        });
        k2.body.addView(add2);
        Ui.note(c, k2.body, "寒暑假 / 周末 / 法定假日用的是这一组。");
        box.addView(k2.view);
    }

    // ---------- TV端参数（跳转独立页面） ----------
    private void buildNetwork(Context c, JSONObject p) {
        Ui.Card k = Ui.card(c, "TV端参数");
        MaterialButton go = Ui.button(c, "打开 TV端参数", false);
        go.setOnClickListener(v -> startActivity(new Intent(c, TvParamsActivity.class)));
        k.body.addView(go);
        Ui.note(c, k.body, "电视端 IP / MAC、群晖 IP、出网网口、局域网段等参数在这个页面里改。\n"
                + "一般不需要动；改错可点页面里的「恢复默认值」。");
        box.addView(k.view);
    }

    // ---------- 保存 ----------
    private void buildSave(Context c) {
        Ui.Card k = Ui.card(c, "保存");
        MaterialButton save = Ui.button(c, "保存并立即生效", true);
        save.setOnClickListener(v -> saveAll(c));
        k.body.addView(save);
        Ui.note(c, k.body, "本页所有改动（模式、开关、时段）都只先记在手机上，\n"
                + "点这里才下发到群晖并立刻应用；刷新或重开 App 会丢弃未保存的改动。");
        box.addView(k.view);
    }

    // ---------------------------------------------------------- 工具

    private SwitchCompat switchOf(Context c, LinearLayout parent, String label,
                                  boolean checked, String hint) {
        SwitchCompat s = new SwitchCompat(c);
        s.setText(label);
        s.setTextSize(14f);
        s.setChecked(checked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(c, 2), 0, Ui.dp(c, 2));
        s.setLayoutParams(lp);
        parent.addView(s);
        if (hint != null && !hint.isEmpty()) {
            TextView t = Ui.text(c, hint, 11.5f, Ui.TEXT2, false);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.setMargins(Ui.dp(c, 4), 0, 0, Ui.dp(c, 6));
            t.setLayoutParams(hp);
            parent.addView(t);
        }
        return s;
    }

    private Spinner spinner(Context c, LinearLayout parent, String[] items) {
        Spinner s = new Spinner(c);
        ArrayAdapter<String> ad = new ArrayAdapter<>(c,
                android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(ad);
        parent.addView(s);
        return s;
    }

    private void setSpinner(Spinner s, String val) {
        for (int i = 0; i < s.getAdapter().getCount(); i++) {
            if (val != null && val.equals(s.getAdapter().getItem(i))) {
                s.setSelection(i);
                return;
            }
        }
    }

    private static List<int[]> parse(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null) return out;
        for (String seg : s.split(";")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            String[] ab = seg.split("-");
            if (ab.length != 2) continue;
            int a = toMin(ab[0]);
            int b = toMin(ab[1]);
            if (a < 0 || b < 0) continue;
            out.add(new int[]{a, b});
        }
        return out;
    }

    private static int toMin(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private void renderRows(Context c, LinearLayout container, List<int[]> list) {
        container.removeAllViews();
        if (list.isEmpty()) {
            TextView t = Ui.text(c, "（未设置任何时段 = 全天不开放）", 13f, Ui.TEXT2, false);
            t.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
            container.addView(t);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            int[] seg = list.get(i);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));

            MaterialButton bs = Ui.button(c, fmt(seg[0]), false);
            bs.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bs.setOnClickListener(v -> pick(c, list, idx, 0, container));

            TextView wave = Ui.text(c, "→", 14f, Ui.TEXT2, true);
            wave.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);

            MaterialButton be = Ui.button(c, fmt(seg[1]), false);
            be.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            be.setOnClickListener(v -> pick(c, list, idx, 1, container));

            MaterialButton del = Ui.button(c, "删除", false);
            del.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            del.setTextColor(0xFFC62828);
            del.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFC62828));
            del.setOnClickListener(v -> {
                list.remove(idx);
                renderRows(c, container, list);
            });

            row.addView(bs);
            row.addView(wave);
            row.addView(be);
            row.addView(del);
            container.addView(row);
        }
    }

    private void pick(Context c, List<int[]> list, int idx, int which, LinearLayout container) {
        int cur = list.get(idx)[which];
        new TimePickerDialog(c, (view, h, m) -> {
            list.get(idx)[which] = h * 60 + m;
            renderRows(c, container, list);
        }, cur / 60, cur % 60, true).show();
    }

    private String join(List<int[]> list) {
        List<int[]> copy = new ArrayList<>(list);
        Collections.sort(copy, Comparator.comparingInt(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        for (int[] s : copy) {
            if (sb.length() > 0) sb.append(";");
            sb.append(fmt(s[0])).append("-").append(fmt(s[1]));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- 动作

    private void saveAll(Context c) {
        for (int[] s : school) {
            if (s[1] <= s[0]) {
                toast("上学日有一段结束时间不晚于开始时间，请修正");
                return;
            }
        }
        for (int[] s : holiday) {
            if (s[1] <= s[0]) {
                toast("放假日有一段结束时间不晚于开始时间，请修正");
                return;
            }
        }
        final String sw = join(school);
        final String hw = join(holiday);

        runAsync(() -> {
            // 1) 电视端开关（UI 开=放行，conf 里 1=封禁，所以取反写入；
            //    「封禁时踢掉 SMB 会话」隐藏项固定按默认执行=1）
            JSONObject ps = new JSONObject();
            ps.put("BLOCK_WAN", swWan.isChecked() ? "0" : "1");
            ps.put("BLOCK_NAS", swNas.isChecked() ? "0" : "1");
            ps.put("KICK_PROJECTOR_SMB", "1");
            ps.put("BLOCK_MODE", String.valueOf(spMode.getSelectedItem()));
            JSONObject b1 = new JSONObject();
            b1.put("scope", "projector");
            b1.put("set", ps);
            b1.put("apply", true);
            Api.R r1 = Conn.post(ctx(), "/api/config", b1, Api.T_LONG);
            if (!r1.ok) return r1;

            // 2) 开放时段
            JSONObject set = new JSONObject();
            set.put("SCHOOL_WINDOWS", sw);
            set.put("HOLIDAY_WINDOWS", hw);
            JSONObject b2 = new JSONObject();
            b2.put("scope", "calendar");
            b2.put("set", set);
            b2.put("apply", true);
            Api.R r2 = Conn.post(ctx(), "/api/config", b2, Api.T_LONG);
            if (!r2.ok) return r2;

            // 3) 待保存的模式：此时才真正下发
            if (pendingMode != null) {
                JSONObject a = new JSONObject();
                a.put("action", "block".equals(pendingMode) ? "proj_on"
                        : "open".equals(pendingMode) ? "proj_off" : "proj_auto");
                return Conn.post(ctx(), "/api/action", a, Api.T_LONG);
            }
            return r2;
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                toast("已保存并生效");
                showOutput("保存结果", "已改动：" + r.json.optString("changed", "")
                        + "\n\n" + r.out());
                load();
            } else {
                showOutput("保存失败", r.msg() + (r.json != null ? "\n\n" + r.out() : ""));
            }
        });
    }
}
