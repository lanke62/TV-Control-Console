package com.lanke.nastools;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

/** 设备页：被控设备列表，增 / 删都在这里做；增删会立即同步 NAS 并重建防火墙规则 */
public class DeviceListFragment extends BaseFragment {

    private LinearLayout box;
    private JSONArray devices = new JSONArray();

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
        if (box != null) load();
    }

    @Override
    public void load() {
        runAsync(() -> Conn.get(ctx(), "/api/devices", null, Api.T_NORMAL), o -> {
            Context c = ctx();
            if (c == null) return;
            box.removeAllViews();
            if (!(o instanceof Api.R) || !((Api.R) o).ok) {
                String msg = (o instanceof Api.R) ? ((Api.R) o).msg() : "未知错误";
                box.addView(Ui.errorBox(c, msg));
                return;
            }
            devices = ((Api.R) o).json.optJSONArray("devices");
            if (devices == null) devices = new JSONArray();
            build(c);
        });
    }

    private void build(Context c) {
        Ui.Card head = Ui.card(c, "被控设备（" + devices.length() + "）");
        MaterialButton add = Ui.button(c, "+ 添加设备", true);
        add.setOnClickListener(v -> showAdd(c));
        head.body.addView(add);
        Ui.note(c, head.body, "按 MAC 识别设备，不需要固定 IP。\n"
                + "MAC 在设备本机看：安卓「设置 → WLAN → 当前网络」或「关于手机 → WLAN MAC 地址」；\n"
                + "iPhone「设置 → 无线局域网 → ⓘ」里的「无线局域网地址」（关掉「私有地址」更稳）。");
        box.addView(head.view);

        if (devices.length() == 0) {
            Ui.Card k = Ui.card(c, "列表是空的");
            Ui.note(c, k.body, "点上面的「+ 添加设备」加第一台。");
            box.addView(k.view);
            return;
        }

        for (int i = 0; i < devices.length(); i++) {
            final JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            final String id = d.optString("id", "");
            final String name = d.optString("name", "-");

            Ui.Card k = Ui.card(c, name);
            Ui.row(c, k.body, "MAC", d.optString("mac", "-"));
            String note = d.optString("note", "");
            if (!note.isEmpty()) Ui.row(c, k.body, "备注", note);
            Ui.row(c, k.body, "模式", modeName(d.optString("mode", "auto")));
            Ui.row(c, k.body, "状态", d.optInt("enabled", 1) == 1 ? "已启用" : "已停用");

            LinearLayout r = Ui.buttonRow(c, 2);
            MaterialButton del = Ui.button(c, "删除", false);
            del.setTextColor(0xFFC62828);
            del.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFC62828));
            del.setOnClickListener(v -> confirmDelete(c, id, name, d.optString("mac", "")));
            Ui.addToRow(c, r, del);
            k.body.addView(r);
            box.addView(k.view);
        }
    }

    private String modeName(String m) {
        if ("open".equals(m)) return "长期放行";
        if ("block".equals(m)) return "长期封禁";
        return "校历模式";
    }

    // ---------------------------------------------------------- 添加

    private void showAdd(Context c) {
        LinearLayout box = Ui.vbox(c, 16);

        TextView l1 = Ui.text(c, "设备名称", 12.5f, Ui.TEXT2, false);
        box.addView(l1);
        final EditText etName = input(c, "例如：客厅电视 / 孩子的手机", false);
        box.addView(etName);

        TextView l2 = Ui.text(c, "MAC 地址", 12.5f, Ui.TEXT2, false);
        l2.setPadding(0, Ui.dp(c, 10), 0, 0);
        box.addView(l2);
        final EditText etMac = input(c, "D0:96:EA:0E:00:07", false);
        box.addView(etMac);

        TextView l3 = Ui.text(c, "备注（可不填）", 12.5f, Ui.TEXT2, false);
        l3.setPadding(0, Ui.dp(c, 10), 0, 0);
        box.addView(l3);
        final EditText etNote = input(c, "例如：孩子卧室", false);
        box.addView(etNote);

        Ui.note(c, box, "保存后会在 NAS 上立即建规则并生效。\n"
                + "MAC 写错不会报错，但那台设备就管不到——务必核对。");

        new AlertDialog.Builder(c)
                .setTitle("添加被控设备")
                .setView(box)
                .setPositiveButton("添加", (dlg, w) -> add(c,
                        String.valueOf(etName.getText()).trim(),
                        String.valueOf(etMac.getText()).trim(),
                        String.valueOf(etNote.getText()).trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText input(Context c, String hint, boolean numeric) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14f);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private void add(Context c, String name, String mac, String note) {
        if (name.isEmpty()) {
            toast("名称不能为空");
            return;
        }
        if (mac.isEmpty()) {
            toast("MAC 不能为空");
            return;
        }
        runAsync(() -> {
            JSONObject body = new JSONObject();
            body.put("op", "add");
            body.put("name", name);
            body.put("mac", mac);
            body.put("note", note);
            return Conn.post(ctx(), "/api/devices", body, Api.T_LONG);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                toast("已添加并生效");
                JSONObject ap = r.json == null ? null : r.json.optJSONObject("apply");
                if (ap != null) {
                    String err = joinErrors(ap.optJSONArray("errors"));
                    if (!err.isEmpty()) showOutput("规则下发有问题", err);
                }
                load();
            } else {
                showOutput("添加失败", r.msg());
            }
        });
    }

    // ---------------------------------------------------------- 删除

    private void confirmDelete(Context c, String id, String name, String mac) {
        new AlertDialog.Builder(c)
                .setTitle("删除「" + name + "」？")
                .setMessage("会立即同步到 NAS，并清掉这台设备的全部防火墙规则与残留。\n"
                        + "删掉之后这台设备就不受管控了。\n\nMAC：" + mac)
                .setPositiveButton("删除", (d, w) -> delete(c, id))
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(Context c, String id) {
        runAsync(() -> {
            JSONObject body = new JSONObject();
            body.put("op", "del");
            body.put("id", id);
            return Conn.post(ctx(), "/api/devices", body, Api.T_LONG);
        }, o -> {
            if (!(o instanceof Api.R)) return;
            Api.R r = (Api.R) o;
            if (r.ok) {
                toast("已删除并清空规则");
                load();
            } else {
                showOutput("删除失败", r.msg());
            }
        });
    }

    private String joinErrors(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            sb.append("· ").append(arr.optString(i)).append("\n");
        }
        return sb.toString().trim();
    }
}
