package com.lanke.nastools;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
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
 * TV端参数页面：电视端 IP / MAC、群晖 IP、出网网口、局域网段。
 * 从独立功能开关页跳转进来；群晖 IP 默认跟随设置页的 NAS 地址。
 * 提供「恢复默认值」防止改错改不回来。
 */
public class TvParamsActivity extends AppCompatActivity {

    private EditText etIp, etMac, etNasIp, etWanIf, etLanNet;
    private LinearLayout body;
    private ProgressDialog loading;

    // 进入本页时 NAS 上生效的值，供「恢复默认值」回滚
    private String loadedIp = "", loadedMac = "", loadedWanIf = "", loadedLanNet = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);

        Toolbar tb = new Toolbar(this);
        tb.setTitle("TV端参数");
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setBackgroundColor(Ui.col(this, R.color.brand));
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56)));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        body = Ui.vbox(this, 4);
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etIp = field(body, "电视端 IP");
        etMac = field(body, "电视端 MAC");
        etNasIp = field(body, "群晖 IP（电视端网关栏要填这个地址）");
        etWanIf = field(body, "出网网口 WAN_IF");
        etLanNet = field(body, "局域网段 LAN_NET");

        Ui.note(this, body, "出网网口装了 VMM / Container Manager 后是 ovs_eth0 而不是 eth0，\n"
                + "写错会导致封禁 NAT 完全不生效。\n"
                + "确认方法（群晖 SSH）：ip route | grep default，看 dev 后面的名字。");

        MaterialButton save = Ui.button(this, "保存并立即生效", true);
        save.setOnClickListener(v -> save());
        body.addView(save);

        MaterialButton reset = Ui.button(this, "恢复默认值", false);
        reset.setOnClickListener(v -> resetDefaults());
        body.addView(reset);

        Ui.note(this, body, "「恢复默认值」把各项恢复为本页打开时 NAS 上生效的值；"
                + "群晖 IP 恢复为设置页的 NAS 地址。恢复后仍需点「保存并立即生效」才会应用。");

        load();
    }

    private EditText field(LinearLayout parent, String label) {
        TextView lb = Ui.text(this, label, 12.5f, Ui.TEXT2, false);
        lb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        parent.addView(lb);
        EditText e = new EditText(this);
        e.setTextSize(14f);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(e);
        return e;
    }

    private void load() {
        loading = ProgressDialog.show(this, null, "正在读取参数…", true, false);
        final Context c = getApplicationContext();
        new Thread(() -> {
            Api.R r = Conn.get(c, "/api/status", null, Api.T_NORMAL);
            runOnUiThread(() -> {
                if (loading != null) {
                    loading.dismiss();
                    loading = null;
                }
                if (isFinishing() || isDestroyed()) return;
                if (r == null || !r.ok) {
                    Ui.note(this, body, "读取失败：" + (r == null ? "未知错误" : r.msg())
                            + "，请回设置页检查连接。");
                    return;
                }
                JSONObject p = r.json.optJSONObject("projector");
                if (p == null) p = new JSONObject();
                loadedIp = p.optString("ip", "");
                loadedMac = p.optString("mac", "");
                loadedWanIf = p.optString("wan_if", "");
                loadedLanNet = p.optString("lan_net", "");
                String nasIp = p.optString("nas_ip", "");
                if (nasIp.isEmpty()) nasIp = Prefs.host(this); // 默认跟随设置页 NAS 地址
                etIp.setText(loadedIp);
                etMac.setText(loadedMac);
                etNasIp.setText(nasIp);
                etWanIf.setText(loadedWanIf);
                etLanNet.setText(loadedLanNet);
            });
        }, "tv-params-load").start();
    }

    private void resetDefaults() {
        etIp.setText(loadedIp);
        etMac.setText(loadedMac);
        etNasIp.setText(Prefs.host(this));
        etWanIf.setText(loadedWanIf);
        etLanNet.setText(loadedLanNet);
        Ui.toast(this, "已恢复为本页打开时的值，记得点「保存并立即生效」");
    }

    private void save() {
        final String ip = etIp.getText().toString().trim();
        final String nasIp = etNasIp.getText().toString().trim();
        if (ip.isEmpty() || nasIp.isEmpty()) {
            Ui.toast(this, "电视端 IP 和群晖 IP 不能为空");
            return;
        }
        ProgressDialog pd = ProgressDialog.show(this, null, "正在保存并应用…", true, false);
        final Context c = getApplicationContext();
        final String mac = etMac.getText().toString().trim();
        final String wanIf = etWanIf.getText().toString().trim();
        final String lanNet = etLanNet.getText().toString().trim();
        new Thread(() -> {
            JSONObject set = new JSONObject();
            try {
                set.put("PROJECTOR_IP", ip);
                set.put("PROJECTOR_MAC", mac);
                set.put("NAS_IP", nasIp);
                set.put("WAN_IF", wanIf);
                set.put("LAN_NET", lanNet);
            } catch (Exception ignored) {
            }
            JSONObject bodyReq = new JSONObject();
            try {
                bodyReq.put("scope", "projector");
                bodyReq.put("set", set);
                bodyReq.put("apply", true);
            } catch (Exception ignored) {
            }
            Api.R r = Conn.post(c, "/api/config", bodyReq, Api.T_LONG);
            runOnUiThread(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (r != null && r.ok) {
                    Ui.toast(this, "已保存并生效");
                    loadedIp = ip;
                    loadedMac = mac;
                    loadedWanIf = wanIf;
                    loadedLanNet = lanNet;
                } else {
                    Ui.note(this, body, "保存失败：" + (r == null ? "未知错误" : r.msg()));
                }
            });
        }, "tv-params-save").start();
    }
}
