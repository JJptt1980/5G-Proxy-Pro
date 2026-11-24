#include "socks5_protocol.h"

#include <string.h>
#include <strings.h>

int socks5_method_offered(const unsigned char *methods, int nmethods, int desired_method) {
    for (int i = 0; i < nmethods; i++) {
        if (methods[i] == desired_method) return 1;
    }
    return 0;
}

int socks5_check_credentials(const unsigned char *user, unsigned char ulen,
                             const unsigned char *pass, unsigned char plen,
                             const char *expected_user, const char *expected_pass) {
    // 帳號與密碼都必須完全相符（啟用認證時兩欄皆非空，因此不允許空值放行）。
    // 註：此為 LAN 代理、非對外服務，比對未做恆時（timing-safe），與舊實作一致。
    int user_ok = (ulen == (unsigned char)strlen(expected_user)) &&
                  memcmp(user, expected_user, ulen) == 0;
    int pass_ok = (plen == (unsigned char)strlen(expected_pass)) &&
                  memcmp(pass, expected_pass, plen) == 0;
    return user_ok && pass_ok;
}

int socks5_validate_request_header(const unsigned char *req) {
    // req[0]=VER, req[1]=CMD, req[2]=RSV, req[3]=ATYP
    if (req[0] != 0x05) return -1;  // 必須是 SOCKS5
    if (req[2] != 0x00) return -1;  // RFC 1928：RSV 欄位必須為 0x00
    int atyp = req[3];
    if (atyp != 0x01 && atyp != 0x03 && atyp != 0x04) return -1;  // 不支援的地址類型
    return 0;
}

int socks5_is_supported_cmd(int cmd) {
    return cmd == 0x01 || cmd == 0x03 || cmd == 0x04;
}

int socks5_udp_parse(const unsigned char *dgram, size_t len,
                     unsigned char *atyp, const unsigned char **addr, unsigned char port[2]) {
    if (len < 4) return -1; /* 至少 RSV(2)+FRAG(1)+ATYP(1) */
    if (dgram[0] != 0 || dgram[1] != 0) return -1; /* RSV 須為 0（RFC 1928） */
    if (dgram[2] != 0) return -1;                 /* FRAG 須為 0 */
    unsigned char t = dgram[3];
    if (t == SOCKS5_ATYP_IPV4) {
        if (len < 10) return -1; /* 表頭 4+addr(4)+port(2) */
        if (atyp) *atyp = t;
        if (addr) *addr = dgram + 4;
        if (port) memcpy(port, dgram + 8, 2);
        return 10;
    }
    if (t == SOCKS5_ATYP_IPV6) {
        if (len < 22) return -1; /* 表頭 4+addr(16)+port(2) */
        if (atyp) *atyp = t;
        if (addr) *addr = dgram + 4;
        if (port) memcpy(port, dgram + 20, 2);
        return 22;
    }
    if (t == SOCKS5_ATYP_DOMAIN) {
        /* RFC 1928 §7 的 UDP request header 明文允許 ATYP=0x03（網域）。
         * 舊版在此直接回 -1（註解誤稱「UDP datagram 不允許」），導致 5G-Proxy-Client
         * 開啟 Remote DNS 時送出的網域 frame 被靜默丟棄（QUIC 等 UDP 全滅）。
         * 表頭：4 + 長度欄(1) + 網域(dl) + port(2)。 */
        if (len < 5) return -1; /* 連長度欄都讀不到 */
        unsigned char dl = dgram[4];
        if (dl == 0) return -1; /* 空網域：無意義且無法解析 */
        if (len < (size_t)5 + (size_t)dl + 2) return -1; /* 網域或 port 不完整 */
        if (atyp) *atyp = t;
        if (addr) *addr = dgram + 4; /* 指向長度欄：addr[0]=dl、addr+1 起為網域 */
        if (port) memcpy(port, dgram + 5 + dl, 2);
        return 5 + (int)dl + 2;
    }
    return -1; /* 其他 ATYP：不支援 */
}

/* 寫入 [ATYP][ADDR][PORT] 至 p（p[0]=ATYP），回傳 7（v4）或 19（v6）bytes。
 * UDP datagram 表頭與 SOCKS5 回覆共用此段（差異只在前面 3 bytes），
 * 收斂在此避免兩處各自貼上造成位址/埠偏移漂移。 */
static int encode_addr_port(unsigned char *p, int is_v6, const unsigned char *addr, const unsigned char port[2]) {
    if (!is_v6) {
        p[0] = SOCKS5_ATYP_IPV4;
        memcpy(p + 1, addr, 4);
        memcpy(p + 5, port, 2);
        return 7;
    }
    p[0] = SOCKS5_ATYP_IPV6;
    memcpy(p + 1, addr, 16);
    memcpy(p + 17, port, 2);
    return 19;
}

int socks5_udp_encode(unsigned char *out, int is_v6, const unsigned char *addr, const unsigned char port[2]) {
    out[0] = 0; out[1] = 0; out[2] = 0; /* RSV(2) + FRAG(1) */
    return 3 + encode_addr_port(out + 3, is_v6, addr, port);
}

int socks5_encode_reply(unsigned char *out, unsigned char rep, int is_v6,
                        const unsigned char *addr, const unsigned char port[2]) {
    out[0] = 0x05; /* VER */
    out[1] = rep;
    out[2] = 0x00; /* RSV */
    return 3 + encode_addr_port(out + 3, is_v6, addr, port);
}

int socks5_udp_tcp_frame_len(const unsigned char len_field[2], int max_len) {
    int dlen = (len_field[0] << 8) | len_field[1];
    if (dlen < 4 || dlen > max_len) return -1; /* 協定違規：裝不下表頭或爆緩衝 */
    return dlen;
}

int socks5_request_addr_len(unsigned char atyp, unsigned char first_byte) {
    if (atyp == SOCKS5_ATYP_IPV4) return 4;
    if (atyp == SOCKS5_ATYP_IPV6) return 16;
    if (atyp == SOCKS5_ATYP_DOMAIN) return first_byte >= 1 ? (int)first_byte : -1;
    return -1;
}

void socks5_addr_normalize(const unsigned char *src, int is_v6, unsigned char *out16) {
    memset(out16, 0, 16);
    if (!is_v6) {
        out16[10] = 0xff; out16[11] = 0xff; /* ::ffff:a.b.c.d */
        memcpy(out16 + 12, src, 4);
    } else {
        memcpy(out16, src, 16);
    }
}

/* [誰在用] Client GW 報名用的假網域：「<ID>.<名字hex>.gw-id.invalid」。 */
#define GW_ID_SUFFIX ".gw-id.invalid"
int socks5_is_gw_id_host(const char *host) {
    if (!host) return 0;
    size_t n = strlen(host), m = strlen(GW_ID_SUFFIX);
    return n > m && strcasecmp(host + n - m, GW_ID_SUFFIX) == 0;
}

/* 取出後綴前的部分，只留 [0-9a-zA-Z.-]；空的或太長回 -1。 */
int socks5_gw_id_tag(const char *host, char *out, size_t outlen) {
    if (!socks5_is_gw_id_host(host) || !out || outlen == 0) return -1;
    size_t n = strlen(host) - strlen(GW_ID_SUFFIX);
    if (n == 0 || n >= outlen) return -1;
    for (size_t i = 0; i < n; i++) {
        char c = host[i];
        int ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '-';
        if (!ok) return -1;
        out[i] = c;
    }
    out[n] = '\0';
    return 0;
}

/* [誰在用] 看連去的網域猜品牌。只回代碼，不保留網域本身。
 * 只收「這個牌子的裝置自己會連」的系統網域，App 網域（例如 Apple Music）不收。 */
typedef struct { const char *suffix; int brand; } brand_rule_t;
static const brand_rule_t k_brand_rules[] = {
    {"vivo.com.cn", SOCKS5_BRAND_VIVO}, {"vivo.com", SOCKS5_BRAND_VIVO}, {"vivoglobal.com", SOCKS5_BRAND_VIVO},
    {"icloud.com", SOCKS5_BRAND_APPLE}, {"apple.com", SOCKS5_BRAND_APPLE}, {"mzstatic.com", SOCKS5_BRAND_APPLE},
    {"windowsupdate.com", SOCKS5_BRAND_WINDOWS}, {"msftconnecttest.com", SOCKS5_BRAND_WINDOWS}, {"msftncsi.com", SOCKS5_BRAND_WINDOWS},
    {"samsungcloud.com", SOCKS5_BRAND_SAMSUNG}, {"samsungapps.com", SOCKS5_BRAND_SAMSUNG}, {"samsungdm.com", SOCKS5_BRAND_SAMSUNG},
    {"xiaomi.com", SOCKS5_BRAND_XIAOMI}, {"miui.com", SOCKS5_BRAND_XIAOMI}, {"xiaomi.net", SOCKS5_BRAND_XIAOMI},
    {"heytapmobi.com", SOCKS5_BRAND_OPPO}, {"oppomobile.com", SOCKS5_BRAND_OPPO}, {"coloros.com", SOCKS5_BRAND_OPPO},
    {"hicloud.com", SOCKS5_BRAND_HUAWEI}, {"dbankcloud.com", SOCKS5_BRAND_HUAWEI},
};

int socks5_guess_brand(const char *host) {
    if (!host) return SOCKS5_BRAND_NONE;
    size_t n = strlen(host);
    while (n > 0 && host[n - 1] == '.') n--;   /* 結尾的點 */
    for (size_t i = 0; i < sizeof(k_brand_rules) / sizeof(k_brand_rules[0]); i++) {
        const char *suf = k_brand_rules[i].suffix;
        size_t m = strlen(suf);
        if (n < m || strncasecmp(host + n - m, suf, m) != 0) continue;
        if (n == m || host[n - m - 1] == '.') return k_brand_rules[i].brand;  /* 整段相符，不吃 notvivo.com */
    }
    return SOCKS5_BRAND_NONE;
}

const char *socks5_brand_code(int brand) {
    static const char *codes[] = {"", "vivo", "apple", "windows", "samsung", "xiaomi", "oppo", "huawei"};
    return (brand > 0 && brand < SOCKS5_BRAND_COUNT) ? codes[brand] : "";
}
