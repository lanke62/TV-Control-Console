package com.lanke.nastools;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.text.InputType;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 校历：月历查看 + 单日覆盖 + 重建/联网更新 + 寒暑假区间 + 学段切换 */
public class CalendarFragment extends BaseFragment {

    private LinearLayout box;
    private String ym;                       // 当前显示的月份 YYYY-MM
    private final Map<String, JSONObject> dayMap = new HashMap<>();
    private TextView tvMonth;
    private Spinner spStage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle b) {
        Context c = requireContext();
        box = Ui.vbox(c, 8);
        ym = String.format(Locale.US, "%04d-%02d",
                Calendar.getInstance().get(Calendar.YEAR),
                Calendar.getInstance().get(Calendar.MONTH) + 1);
        return makeScroller(c, box);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (box != null && box.getChildCount() == 0) load();
    }

    @Override
    public void load() {
        if (ym == null) return;
        runAsync(() -> {
            java.util.Map<String, String> q = new java.util.HashMap<>();
            q.put("ym", ym);
            Api.R rm = Conn.get(ctx(), "/api/calendar", q, Api.T_NORMAL);
            Api.R rs = Conn.get(ctx(), "/api/status", null, Api.T_NORMAL);
            Api.R ro = Conn.get(ctx(), "/api/overrides", null, Api.T_NORMAL);
            JSONObject merged = new JSONObject();
            merged.put("month", rm.json);
            merged.put("status", rs.json);
            merged.put("overrides", ro.json);
            Api.R out = new Api.R();
            out.ok = rm.ok;
            out.error = rm.ok ? null : rm.msg();
            out.json = merged;
            return out;
        }, o -> {
            Context c = ctx();
            if (c == null) return;
            if (!(o instanceof Api.R) || !((Api.R) o).ok) {
                String msg = (o instanceof Api.R) ? ((Api.R) o).msg() : "未知错误";
                box.removeAllViews();
                box.addView(Ui.errorBox(c, msg + "\n\n当前设置："
                        + Prefs.host(c) + ":" + Prefs.sshPort(c)));
                return;
            }
            dayMap.clear();
            JSONObject mo = ((Api.R) o).json.optJSONObject("month");
            if (mo != null) {
                JSONArray arr = mo.optJSONArray("days");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject j = arr.optJSONObject(i);
                        if (j != null) dayMap.put(j.optString("date"), j);
                    }
                }
            }
            build(c, ((Api.R) o).json.optJSONObject("status"));
        });
    }

    // ---------------------------------------------------------- 界面

    private void build(Context c, JSONObject status) {
        box.removeAllViews();

        // ---------- 学段（小学/高中）----------
        // STAGE 是 calendar.conf 里的值，NAS 侧可能放在 media 或 calendar 对象里，
        // 这里做防御性多源读取，避免另一个 AI 改了状态结构后读不到。
        String stage = pickStr(status, "stage", "primary");
        Ui.Card ks = Ui.card(c, "学段（校历适用范围）");
        TextView lbStage = Ui.text(c, "决定按哪套校历判定上学/放假", 12f, Ui.TEXT2, false);
        lbStage.setPadding(0, 0, 0, Ui.dp(c, 4));
        ks.body.addView(lbStage);
        spStage = new Spinner(c);
        ArrayAdapter<String> ad = new ArrayAdapter<>(c,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"小学", "高中"});
        spStage.setAdapter(ad);
        spStage.setSelection("high".equals(stage) ? 1 : 0);
        final boolean[] stageReady = {false};
        spStage.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (!stageReady[0]) {
                    stageReady[0] = true;   // 初次设置不触发重建
                    return;
                }
                String v = position == 1 ? "high" : "primary";
                if (!v.equals(stage)) setStage(v);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        ks.body.addView(spStage);
        box.addView(ks.view);

        // ---------- 月份导航 ----------
        Ui.Card k0 = Ui.card(c, "校历月视图");
        LinearLayout nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton prev = Ui.button(c, "◀", false);
        prev.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        prev.setOnClickListener(v -> moveMonth(-1));
        tvMonth = Ui.text(c, ym, 17f, Ui.TEXT, true);
        tvMonth.setGravity(Gravity.CENTER);
        tvMonth.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton next = Ui.button(c, "▶", false);
        next.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        next.setOnClickListener(v -> moveMonth(1));
        nav.addView(prev);
        nav.addView(tvMonth);
        nav.addView(next);
        k0.body.addView(nav);

        k0.body.addView(monthGrid(c));
        box.addView(k0.view);

        // ---------- 数据库 ----------
        JSONObject cal = status != null ? status.optJSONObject("calendar") : null;
        Ui.Card k1 = Ui.card(c, "校历数据库");
        if (cal != null) {
            Ui.row(c, k1.body, "覆盖范围",
                    cal.optString("range_start", "") + " ~ " + cal.optString("range_end", ""));
            Ui.row(c, k1.body, "总天数", String.valueOf(cal.optInt("days", 0)));
            Ui.row(c, k1.body, "上学 / 不上学",
                    cal.optInt("school_days", 0) + " / " + cal.optInt("holiday_days", 0));
            Ui.row(c, k1.body, "最后构建", cal.optString("built_at", "-"));
        }
        LinearLayout r = Ui.buttonRow(c, 2);
        MaterialButton b1 = Ui.button(c, "重建（增量）", false);
        b1.setOnClickListener(v -> doBuild(false));
        MaterialButton b2 = Ui.button(c, "联网更新节假日", false);
        b2.setOnClickListener(v -> doBuild(true));
        Ui.addToRow(c, r, b1);
        Ui.addToRow(c, r, b2);
        k1.body.addView(r);
        Ui.note(c, k1.body,
                "• 重建（增量）：只按本地数据重算，秒级完成。改了寒暑假区间或单日覆盖后点这个。\n"
                        + "• 联网更新：重新抓国务院节假日安排（每年 12 月必须做一次），会花十几秒到几分钟。\n"
                        + "  日志里若出现「使用内置兜底数据」，说明该年国务院还没发布，等发布后再点一次。");
        box.addView(k1.view);

        // ---------- 寒暑假区间 ----------
        String vac = pickStr(status, "vacation_ranges", "");
        Ui.Card k2 = Ui.card(c, "寒暑假区间");
        final LinearLayout vacList = new LinearLayout(c);
        vacList.setOrientation(LinearLayout.VERTICAL);
        k2.body.addView(vacList);
        final java.util.List<VacRange> vacs = new java.util.ArrayList<>();
        for (String seg : vac.split(";")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            String[] p = seg.split("\\|");
            VacRange vr = new VacRange();
            if (p.length >= 1) vr.start = p[0].trim();
            if (p.length >= 2) vr.end = p[1].trim();
            if (p.length >= 3) vr.name = p[2].trim();
            vacs.add(vr);
        }
        renderVac(c, vacList, vacs);
        MaterialButton addV = Ui.button(c, "+ 添加区间", false);
        addV.setOnClickListener(v -> {
            vacs.add(new VacRange());
            renderVac(c, vacList, vacs);
        });
        k2.body.addView(addV);
        MaterialButton bv = Ui.button(c, "保存区间并重建", true);
        bv.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (VacRange vr : vacs) {
                if (vr.start.isEmpty() || vr.end.isEmpty()) continue;
                if (sb.length() > 0) sb.append(";");
                sb.append(vr.start).append("|").append(vr.end).append("|").append(vr.name);
            }
            saveVacation(sb.toString());
        });
        k2.body.addView(bv);
        Ui.note(c, k2.body,
                "区间含首尾两端，结束日 = 开学日的前一天。\n"
                        + "寒暑假的优先级高于法定节假日。\n"
                        + "直接点「开始/结束日期」选日期，不用手输格式。");
        box.addView(k2.view);

        // （单日覆盖卡片已按需求移除：不再提供逐日覆盖的增删界面）
    }

    /** 在 status 的多个子对象里按优先级取某个字符串字段 */
    private String pickStr(JSONObject status, String key, String def) {
        if (status == null) return def;
        String[] objs = {"media", "calendar", "projector"};
        for (String o : objs) {
            JSONObject j = status.optJSONObject(o);
            if (j != null && j.has(key) && !j.isNull(key)) {
                String v = j.optString(key, "");
                if (!v.isEmpty()) return v;
            }
        }
        return def;
    }

    // 月历网格：用嵌套 LinearLayout 实现，彻底避开 GridLayout 的 spec 校验坑
    private View monthGrid(Context c) {
        String[] heads = {"日", "一", "二", "三", "四", "五", "六"};
        int y = Integer.parseInt(ym.substring(0, 4));
        int m = Integer.parseInt(ym.substring(5, 7));
        Calendar cal = Calendar.getInstance();
        cal.set(y, m - 1, 1, 0, 0, 0);
        int firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1;   // 0=周日
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        String today = String.format(Locale.US, "%04d-%02d-%02d",
                Calendar.getInstance().get(Calendar.YEAR),
                Calendar.getInstance().get(Calendar.MONTH) + 1,
                Calendar.getInstance().get(Calendar.DAY_OF_MONTH));
        int cellH = Ui.dp(c, 46);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 表头
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setWeightSum(7);
        for (String h : heads) {
            TextView t = Ui.text(c, h, 12f, Ui.TEXT2, true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Ui.dp(c, 26), 1f);
            t.setLayoutParams(lp);
            header.addView(t);
        }
        root.addView(header);

        // 各周
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                0, cellH, 1f);
        LinearLayout cur = newWeek(c);
        // 首周前面的空格
        for (int i = 0; i < firstDow; i++) cur.addView(blank(c, cellH));

        for (int d = 1; d <= days; d++) {
            final String date = String.format(Locale.US, "%04d-%02d-%02d", y, m, d);
            JSONObject info = dayMap.get(date);
            cur.addView(cell(c, date, info, today, cellH));
            if ((firstDow + d) % 7 == 0) {
                root.addView(cur);
                cur = newWeek(c);
            }
        }
        // 最后一周补尾
        if (cur.getChildCount() > 0) {
            int left = 7 - cur.getChildCount();
            for (int i = 0; i < left; i++) cur.addView(blank(c, cellH));
            root.addView(cur);
        }
        return root;
    }

    private LinearLayout newWeek(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setWeightSum(7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(c, 2), 0, 0);
        l.setLayoutParams(lp);
        return l;
    }

    private View blank(Context c, int cellH) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellH, 1f);
        lp.setMargins(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout cell(Context c, String date, JSONObject info, String today, int cellH) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(Ui.dp(c, 8));
        if (info == null) {
            gd.setColor(0xFFF5F5F5);
        } else if (info.optBoolean("school", false)) {
            gd.setColor(0xFFFFF3E0);
        } else {
            gd.setColor(0xFFE8F5E9);
        }
        boolean hasOvr = info != null && info.opt("override") != null
                && !info.isNull("override");
        if (hasOvr) gd.setStroke(Math.max(2, Ui.dp(c, 2)), 0xFF7B1FA2);
        else if (date.equals(today)) gd.setStroke(Math.max(2, Ui.dp(c, 2)), 0xFF1565C0);
        cell.setBackground(gd);

        TextView t1 = Ui.text(c, String.valueOf(Integer.parseInt(date.substring(8, 10))),
                14f, Ui.TEXT, date.equals(today));
        t1.setGravity(Gravity.CENTER);
        t1.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t2 = Ui.text(c,
                info == null ? " " : (info.optBoolean("school", false) ? "上学" : "放假")
                        + (hasOvr ? " ✱" : ""),
                10f, info == null ? Ui.TEXT2
                        : (info.optBoolean("school", false) ? 0xFFEF6C00 : 0xFF2E7D32),
                false);
        t2.setGravity(Gravity.CENTER);
        t2.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(t1);
        cell.addView(t2);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellH, 1f);
        lp.setMargins(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        cell.setLayoutParams(lp);
        cell.setOnClickListener(v -> showDayDialog(c, date, info));
        return cell;
    }

    private void showDayDialog(Context c, String date, JSONObject info) {
        String type = info == null ? "库里没有这一天"
                : (info.optBoolean("school", false) ? "上学日" : "放假日");
        String cat = info == null ? "" : info.optString("cat", "");
        String label = info == null ? "" : info.optString("label", "");
        JSONObject ovr = (info != null && !info.isNull("override"))
                ? info.optJSONObject("override") : null;

        StringBuilder sb = new StringBuilder();
        sb.append("判定：").append(type);
        if (!cat.isEmpty()) sb.append("\n类别：").append(cat);
        if (!label.isEmpty()) sb.append("\n说明：").append(label);
        if (ovr != null) {
            sb.append("\n人工覆盖：").append("1".equals(ovr.optString("flag")) ? "上学日" : "放假日");
            if (!ovr.optString("note").isEmpty()) sb.append("（").append(ovr.optString("note")).append("）");
        } else {
            sb.append("\n人工覆盖：无");
        }
        sb.append("\n\n上学日 = 走上学日的开放时段（受限）");
        sb.append("\n放假日 = 走放假日的开放时段（宽松）");

        new AlertDialog.Builder(c)
                .setTitle(date)
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------------------------------------------------- 动作

    private void moveMonth(int delta) {
        int y = Integer.parseInt(ym.substring(0, 4));
        int m = Integer.parseInt(ym.substring(5, 7)) - 1 + delta;
        y += m / 12;
        m = ((m % 12) + 12) % 12;
        ym = String.format(Locale.US, "%04d-%02d", y, m + 1);
        load();
    }

    private void setStage(String stage) {
        runAsync(() -> {
            JSONObject set = new JSONObject();
            set.put("STAGE", stage);
            JSONObject b1 = new JSONObject();
            b1.put("scope", "calendar");
            b1.put("set", set);
            b1.put("apply", false);
            Api.R r1 = Conn.post(ctx(), "/api/config", b1, Api.T_LONG);
            if (!r1.ok) return r1;
            JSONObject b2 = new JSONObject();
            b2.put("action", "cal_build");
            return Conn.post(ctx(), "/api/action", b2, Api.T_BUILD);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                toast("已切换学段并重建校历");
                load();
            } else {
                showOutput("切换学段失败", r.msg() + (r.json != null ? "\n\n" + r.out() : ""));
            }
        });
    }

    private void doBuild(boolean init) {
        runAsync(() -> {
            JSONObject b = new JSONObject();
            b.put("action", init ? "cal_init" : "cal_build");
            return Conn.post(ctx(), "/api/action", b, Api.T_BUILD);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            showOutput(init ? "联网更新节假日" : "重建校历",
                    (r.ok ? "" : "失败：" + r.msg() + "\n\n") + r.out());
            load();
        });
    }

    private void saveVacation(String value) {
        runAsync(() -> {
            JSONObject set = new JSONObject();
            set.put("VACATION_RANGES", value);
            JSONObject b = new JSONObject();
            b.put("scope", "calendar");
            b.put("set", set);
            b.put("apply", false);
            Api.R r1 = Conn.post(ctx(), "/api/config", b, Api.T_LONG);
            if (!r1.ok) return r1;
            JSONObject b2 = new JSONObject();
            b2.put("action", "cal_build");
            return Conn.post(ctx(), "/api/action", b2, Api.T_BUILD);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                showOutput("寒暑假区间已保存", r.out());
                load();
            } else {
                showOutput("保存失败", r.msg() + (r.json != null ? "\n\n" + r.out() : ""));
            }
        });
    }

    // 寒暑假区间：每行一段（起始|结束|名称），日期用选日历的方式，不要求手输格式
    private static class VacRange {
        String start = "", end = "", name = "";
    }

    private void renderVac(Context c, LinearLayout container, java.util.List<VacRange> vacs) {
        container.removeAllViews();
        for (int i = 0; i < vacs.size(); i++) {
            final VacRange vr = vacs.get(i);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));

            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            MaterialButton bs = Ui.button(c, vr.start.isEmpty() ? "开始日期" : vr.start, false);
            bs.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bs.setOnClickListener(v -> pickDate(c, vr.start, (y, m, d) -> {
                vr.start = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                renderVac(c, container, vacs);
            }));
            TextView arrow = Ui.text(c, "→", 14f, Ui.TEXT2, true);
            arrow.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);

            MaterialButton be = Ui.button(c, vr.end.isEmpty() ? "结束日期" : vr.end, false);
            be.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            be.setOnClickListener(v -> pickDate(c, vr.end, (y, m, d) -> {
                vr.end = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                renderVac(c, container, vacs);
            }));

            line.addView(bs);
            line.addView(arrow);
            line.addView(be);
            row.addView(line);

            EditText en = new EditText(c);
            en.setHint("名称，如 寒假");
            en.setText(vr.name);
            en.setTextSize(14f);
            en.setSingleLine(true);
            en.setInputType(InputType.TYPE_CLASS_TEXT);
            en.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            en.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    vr.name = s.toString().trim();
                }
            });
            row.addView(en);

            MaterialButton del = Ui.button(c, "删除本区间", false);
            del.setTextColor(0xFFC62828);
            del.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFC62828));
            del.setOnClickListener(v -> {
                vacs.remove(vr);
                renderVac(c, container, vacs);
            });
            row.addView(del);

            container.addView(row);
        }
    }

    private void pickDate(Context c, String cur, DatePick cb) {
        int y = 2026, m = 0, d = 1;
        if (cur != null && cur.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                String[] p = cur.split("-");
                y = Integer.parseInt(p[0]);
                m = Integer.parseInt(p[1]) - 1;
                d = Integer.parseInt(p[2]);
            } catch (Exception ignore) {
            }
        }
        new DatePickerDialog(c, (view, yy, mm, dd) -> cb.onPick(yy, mm, dd), y, m, d).show();
    }

    private interface DatePick {
        void onPick(int y, int m, int d);
    }
}
