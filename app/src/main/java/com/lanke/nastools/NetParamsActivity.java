package com.lanke.nastools;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

/**
 * 网络参数页（全局，不分设备）：出网网口、局域网段、群晖 IP。
 * 设备是按 MAC 认的，所以这里没有「设备 IP / MAC」。
 */
public class NetParamsActivity extends AppCompatActivity {

    private EditText etWanIf, etLanNet, etNasIp;
    private LinearLayout body;

    // 进页时 NAS 上生效的值，供「恢复默认值」回滚
    private String loadedWanIf = "", loadedLanNet = "", loadedNasIp = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);

        Toolbar tb = new Toolbar(this);
        tb.setTitle("网络参数");
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setBackgroundColor(Ui.col(this, R.color.brand));
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        body = Ui.vbox(this, 4);
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etWanIf = field(body, "出网网口 WAN_IF");
        etLanNet = field(body, "局域网段 LAN_NET");
        etNasIp = field(body, "群晖 IP（设备网关要填这个地址）");

        Ui.note(this, body, "出网网口装了 VMM / Container Manager 后是 ovs_eth0 而不是 eth0，\n"
                + "写错会导致封禁完全不生效。\n"
                + "确认方法（群晖 SSH）：ip route | grep default，看 dev 后面的名字。\n\n"
                + "局域网段用来区分「外网」和「内网」：去往该网段的流量不算外网。");

        MaterialButton save = Ui.button(this, "保存并立即生效", true);
        save.setOnClickListener(v -> save());
        body.addView(save);

        MaterialButton reset = Ui.button(this, "恢复默认值", false);
        reset.setOnClickListener(v -> {
            etWanIf.setText(loadedWanIf.isEmpty() ? "ovs_eth0" : loadedWanIf);
            etLanNet.setText(loadedLanNet);
            etNasIp.setText(loadedNasIp.isEmpty() ? Prefs.host(this) : loadedNasIp);
            toast("已恢复为进页时的值，点「保存并立即生效」才会应用");
        });
        body.addView(reset);

        load();
    }

    private EditText field(LinearLayout parent, String label) {
        TextView lb = Ui.text(this, label, 12.5f, Ui.TEXT2, false);
        lb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        parent.addView(lb);
        EditText e = new EditText(this);
        e.setTextSize(14f);
        e.setSingleLine(true);
        parent.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void load() {
        new Thread(() -> {
            Api.R r = Conn.get(this, "/api/net", null, Api.T_NORMAL);
            runOnUiThread(() -> {
                if (r.ok && r.json != null) {
                    JSONObject net = r.json.optJSONObject("net");
                    if (net != null) {
                        loadedWanIf = net.optString("wan_if", "");
                        loadedLanNet = net.optString("lan_net", "");
                        loadedNasIp = net.optString("nas_ip", "");
                    }
                }
                etWanIf.setText(loadedWanIf.isEmpty() ? "ovs_eth0" : loadedWanIf);
                etLanNet.setText(loadedLanNet);
                etNasIp.setText(loadedNasIp.isEmpty() ? Prefs.host(this) : loadedNasIp);
                if (!r.ok) toast("读取失败：" + r.msg());
            });
        }).start();
    }

    private void save() {
        String wan = String.valueOf(etWanIf.getText()).trim();
        String lan = String.valueOf(etLanNet.getText()).trim();
        String nas = String.valueOf(etNasIp.getText()).trim();
        if (wan.isEmpty()) {
            toast("出网网口不能为空");
            return;
        }
        if (lan.isEmpty()) {
            toast("局域网段不能为空");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject set = new JSONObject();
                set.put("WAN_IF", wan);
                set.put("LAN_NET", lan);
                set.put("NAS_IP", nas);
                JSONObject body = new JSONObject();
                body.put("op", "set");
                body.put("set", set);
                Api.R r = Conn.post(this, "/api/net", body, Api.T_LONG);
                runOnUiThread(() -> {
                    if (r.ok) {
                        toast("已保存并生效");
                        JSONObject ap = r.json == null ? null : r.json.optJSONObject("apply");
                        if (ap != null && !ap.optBoolean("ok", true)) {
                            toast("规则下发有问题，请点保存后看提示");
                        }
                        load();
                    } else {
                        toast("保存失败：" + r.msg());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("保存失败：" + e.getMessage()));
            }
        }).start();
    }

    private void toast(String s) {
        Ui.toast(this, s);
    }
}
