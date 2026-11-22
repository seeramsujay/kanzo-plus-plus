#!/usr/bin/env bash
# ==============================================================================
# Kanzo Bunker Tunnel: Fortinet / Firewall Bypass with Pinggy (SSH Port 443)
# and End-to-End AES-256-GCM Encrypted Rendezvous Federation
# ==============================================================================
# Enterprise and campus networks with Fortinet firewalls inspect outbound traffic
# and aggressively drop WireGuard/Tailscale (UDP 41641) and non-standard TCP ports.
#
# This tool establishes an unblockable reverse tunnel via:
#   1. Pinggy: Outbound SSH over TCP port 443 (permitted through TLS inspection)
#   2. Cloudflare Tunnel (cloudflared HTTP2/QUIC over 443)
#   3. WireGuard / Custom reverse proxy
#
# The dynamic tunnel endpoint is encrypted end-to-end using AES-256-GCM and published
# to a high-entropy (48+ hex char) ephemeral ntfy.sh topic.
#
# Device binding is persistent: Pairing once with the Kanzo Mobile Android app saves
# session credentials in ~/.config/kanzo-tunnel/session.json.
# Running `kanzo-tunnel.sh` subsequently reconnects immediately without rescanning.
# ==============================================================================

set -euo pipefail

BOLD="\033[1m"
GREEN="\033[0;32m"
BLUE="\033[0;34m"
YELLOW="\033[1;33m"
CYAN="\033[0;36m"
RED="\033[0;31m"
NC="\033[0m"

CONFIG_DIR="${HOME}/.config/kanzo-tunnel"
CONFIG_FILE="${CONFIG_DIR}/session.json"
PID_FILE="${CONFIG_DIR}/tunnel.pid"
PROXY_PID_FILE="${CONFIG_DIR}/proxy.pid"
LOCK_FILE="${CONFIG_DIR}/tunnel.lock"
LOG_FILE="${CONFIG_DIR}/tunnel.log"
SYSTEMD_USER_DIR="${HOME}/.config/systemd/user"
SERVICE_FILE="${SYSTEMD_USER_DIR}/kanzo-tunnel.service"

mkdir -p "${CONFIG_DIR}"

if ! python3 -c "from cryptography.hazmat.primitives.ciphers.aead import AESGCM" 2>/dev/null; then
    echo -e "${RED}[!] Python 'cryptography' library is required for AES-256-GCM encrypted rendezvous.${NC}"
    echo -e "${YELLOW}    Please install it using: python3 -m pip install cryptography (or apt install python3-cryptography)${NC}"
    exit 1
fi

DEFAULT_KANZO_PORT=4242
DEFAULT_PROXY_PORT=4243
TUNNEL_PROVIDER="pinggy"
CUSTOM_URL=""

show_help() {
    echo -e "${BOLD}Kanzo Bunker Tunnel & Fortinet-Proof Reverse Proxy${NC}"
    echo -e "${CYAN}Secure AES-256-GCM encrypted tunnel via Pinggy SSH 443 for Kanzo.${NC}\n"
    echo -e "${BOLD}USAGE:${NC}"
    echo -e "  kanzo-tunnel.sh [OPTIONS] [PORT]\n"
    echo -e "${BOLD}COMMANDS & FLAGS:${NC}"
    echo -e "  ${GREEN}(no args)${NC}                  Start tunnel with saved device binding (silent, no QR)"
    echo -e "  ${GREEN}-b, --bind${NC}                 Show pairing QR code to bind the Android app"
    echo -e "  ${GREEN}--reset${NC}                    Generate brand new AES-256-GCM keys and channel"
    echo -e "  ${GREEN}-s, --status${NC}               Show tunnel status, active Pinggy URL and service state"
    echo -e "  ${GREEN}--stop${NC}                     Stop running tunnel and reverse proxy"
    echo -e "  ${GREEN}--provider <NAME>${NC}          Tunnel provider: pinggy (default, Fortinet bypass) | cloudflare | custom"
    echo -e "  ${GREEN}--custom-url <URL>${NC}         Specify custom public URL (for wireguard/reverse-proxy)"
    echo -e "  ${GREEN}-p, --port <PORT>${NC}          Local Kanzo board port (default: 4242)"
    echo -e "  ${GREEN}--service <CMD>${NC}            Manage systemd user service: status|start|stop|restart|enable|disable"
    echo -e "  ${GREEN}-h, --help${NC}                 Show this help message\n"
    echo -e "${BOLD}FORTINET / PINGGY BYPASS WORKFLOW:${NC}"
    echo -e "  1. Pair phone:   ${CYAN}tools/kanzo-tunnel.sh --bind${NC}"
    echo -e "  2. Connect daily:${CYAN}tools/kanzo-tunnel.sh${NC}"
    echo -e "  3. Autostart:    ${CYAN}tools/kanzo-tunnel.sh --service enable${NC}\n"
    exit 0
}

manage_service() {
    local cmd="${1:-status}"
    if ! command -v systemctl &>/dev/null; then
        echo -e "${RED}[!] systemctl is not available on this platform.${NC}"
        exit 1
    fi
    case "$cmd" in
        enable)
            mkdir -p "${SYSTEMD_USER_DIR}"
            cat << EOF > "${SERVICE_FILE}"
[Unit]
Description=Kanzo Pinggy Fortinet-Proof Tunnel Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$(readlink -f "$0") --daemon
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=default.target
EOF
            systemctl --user daemon-reload
            systemctl --user enable --now kanzo-tunnel.service
            echo -e "${GREEN}[✓] kanzo-tunnel systemd user service enabled and started!${NC}"
            ;;
        disable)
            systemctl --user stop kanzo-tunnel.service || true
            systemctl --user disable kanzo-tunnel.service || true
            rm -f "${SERVICE_FILE}"
            systemctl --user daemon-reload
            echo -e "${YELLOW}[✓] kanzo-tunnel systemd service disabled and stopped.${NC}"
            ;;
        start|stop|restart|status)
            systemctl --user "$cmd" kanzo-tunnel.service
            ;;
        *)
            echo -e "${RED}[!] Unknown service command: $cmd${NC}"
            exit 1
            ;;
    esac
    exit 0
}

show_status() {
    echo -e "${BOLD}Kanzo Tunnel Status:${NC}"
    echo -e "--------------------------------------------------"
    local running=false
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid=$(cat "${PID_FILE}" 2>/dev/null || true)
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            running=true
        fi
    fi
    if $running; then
        echo -e "Tunnel Process:  ${GREEN}RUNNING (PID: $(cat "${PID_FILE}"))${NC}"
        if [[ -f "${LOG_FILE}" ]]; then
            local active_url
            active_url=$(grep -oE "https://[a-zA-Z0-9.-]+\.pinggy\.link" "${LOG_FILE}" | tail -n 1 || true)
            if [[ -z "$active_url" ]]; then
                active_url=$(grep -oE "https://[a-zA-Z0-9.-]+" "${LOG_FILE}" | grep -v "pinggy.io" | tail -n 1 || true)
            fi
            if [[ -n "${active_url}" ]]; then
                echo -e "Active Pinggy Endpoint: ${CYAN}${active_url}${NC}"
            fi
        fi
    else
        echo -e "Tunnel Process:  ${YELLOW}STOPPED${NC}"
    fi

    if [[ -f "${CONFIG_FILE}" ]]; then
        local topic
        topic=$(python3 -c "import json; print(json.load(open('${CONFIG_FILE}'))['ntfy_topic'])" 2>/dev/null || echo "corrupt")
        echo -e "Saved Binding:   ${GREEN}CONFIGURED${NC} (${topic})"
    else
        echo -e "Saved Binding:   ${YELLOW}NOT CONFIGURED${NC} (Run with --bind)"
    fi
    echo -e "--------------------------------------------------"
    exit 0
}

stop_tunnel() {
    echo -e "${YELLOW}[*] Stopping tunnel and proxy processes...${NC}"
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid=$(cat "${PID_FILE}" 2>/dev/null || true)
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            kill "${pid}" 2>/dev/null || true
        fi
        rm -f "${PID_FILE}"
    fi
    if [[ -f "${PROXY_PID_FILE}" ]]; then
        local ppid
        ppid=$(cat "${PROXY_PID_FILE}" 2>/dev/null || true)
        if [[ -n "${ppid}" ]] && kill -0 "${ppid}" 2>/dev/null; then
            kill "${ppid}" 2>/dev/null || true
        fi
        rm -f "${PROXY_PID_FILE}"
    fi
    pkill -f "tcp@.*pinggy\.io" 2>/dev/null || true
    pkill -f "a\.pinggy\.io" 2>/dev/null || true
    echo -e "${GREEN}[✓] Stopped all tunnel instances.${NC}"
    exit 0
}

# Command Line Parsing
SHOW_BIND_QR=false
RESET_KEYS=false
DAEMON_MODE=false
LOCAL_PORT="${DEFAULT_KANZO_PORT}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            show_help
            ;;
        -b|--bind)
            SHOW_BIND_QR=true
            shift
            ;;
        --reset)
            RESET_KEYS=true
            SHOW_BIND_QR=true
            shift
            ;;
        -s|--status)
            show_status
            ;;
        --stop)
            stop_tunnel
            ;;
        --service)
            manage_service "${2:-status}"
            ;;
        --provider)
            TUNNEL_PROVIDER="$2"
            shift 2
            ;;
        --custom-url)
            CUSTOM_URL="$2"
            TUNNEL_PROVIDER="custom"
            shift 2
            ;;
        --daemon)
            DAEMON_MODE=true
            shift
            ;;
        -p|--port)
            LOCAL_PORT="$2"
            shift 2
            ;;
        *)
            if [[ "$1" =~ ^[0-9]+$ ]]; then
                LOCAL_PORT="$1"
                shift
            else
                echo -e "${RED}[!] Unknown argument: $1${NC}"
                exit 1
            fi
            ;;
    esac
done

acquire_lock() {
    exec 200>"${LOCK_FILE}"
    if command -v flock &>/dev/null; then
        flock -n 200
    else
        python3 -c "import fcntl, sys; fcntl.flock(200, fcntl.LOCK_EX | fcntl.LOCK_NB)" 2>/dev/null || return 1
    fi
}

release_lock() {
    if command -v flock &>/dev/null; then
        flock -u 200 2>/dev/null || true
    fi
    exec 200>&- 2>/dev/null || true
}

cleanup() {
    echo -e "\n${YELLOW}[*] Shutting down tunnel supervisor...${NC}"
    if [[ -n "${TUNNEL_PID:-}" ]] && kill -0 "${TUNNEL_PID}" 2>/dev/null; then
        kill "${TUNNEL_PID}" 2>/dev/null || true
    fi
    if [[ -n "${PROXY_PID:-}" ]] && kill -0 "${PROXY_PID}" 2>/dev/null; then
        kill "${PROXY_PID}" 2>/dev/null || true
    fi
    if [[ -f "${PID_FILE}" ]]; then
        local my_pid
        my_pid=$(cat "${PID_FILE}" 2>/dev/null || true)
        if [[ "$my_pid" == "$$" ]]; then
            rm -f "${PID_FILE}"
        fi
    fi
    if [[ -f "${PROXY_PID_FILE}" ]]; then
        local my_ppid
        my_ppid=$(cat "${PROXY_PID_FILE}" 2>/dev/null || true)
        if [[ "$my_ppid" == "${PROXY_PID:-}" ]]; then
            rm -f "${PROXY_PID_FILE}"
        fi
    fi
    release_lock
    exit 0
}

if ! acquire_lock; then
    echo -e "${RED}[!] Another kanzo-tunnel instance is already running.${NC}"
    exit 1
fi
trap cleanup SIGINT SIGTERM EXIT
echo $$ > "${PID_FILE}"

# Banner
if [[ "$DAEMON_MODE" = false ]]; then
    echo -e "${CYAN}${BOLD}"
    cat << 'EOF'
  _  __                        _____                            _
 | |/ /                       |_   _|                          | |
 | ' / __ _ _ __  _______ ______| |_   _ _ __  _ __   ___| |
 |  < / _` | '_ \|_  / _ \______| | | | | '_ \| '_ \ / _ \ |
 | . \ (_| | | | |/ / (_) |     | | |_| | | | | | | |  __/ |
 |_|\_\__,_|_| |_/___\___/      \_/\__,_|_| |_|_| |_|\___|_|
EOF
    echo -e "${NC}"
    echo -e "${BOLD}🛡️  Kanzo Fortinet Bypass Tunnel (Pinggy SSH 443 + AES-256-GCM) 🛡️${NC}"
    echo -e "======================================================================\n"
fi

# Load or generate persistent session keys
if [[ -f "${CONFIG_FILE}" ]] && [[ "$RESET_KEYS" = false ]]; then
    TOPIC=$(python3 -c "import json; d = json.load(open('${CONFIG_FILE}')); print(d.get('ntfy_topic', ''))")
    KEY_B64=$(python3 -c "import json; d = json.load(open('${CONFIG_FILE}')); print(d.get('key', ''))")
    TOKEN=$(python3 -c "import json; d = json.load(open('${CONFIG_FILE}')); print(d.get('token', ''))")
    if [[ "$DAEMON_MODE" = false ]]; then
        echo -e "${GREEN}[✓] Loaded persistent device binding:${NC}"
        echo -e "    Channel Topic: ${CYAN}${TOPIC}${NC}"
        echo -e "    Target Port:   ${CYAN}${LOCAL_PORT}${NC}"
        echo -e "    AES-256 Key:   ${CYAN}[PROTECTED]${NC}\n"
    fi
else
    TOPIC="kanzo-bunker-$(openssl rand -hex 24)"
    KEY_B64=$(openssl rand 32 | base64 -w 0)
    TOKEN=$(openssl rand -hex 24)

    cat <<EOF > "${CONFIG_FILE}"
{
  "ntfy_topic": "${TOPIC}",
  "key": "${KEY_B64}",
  "token": "${TOKEN}",
  "port": ${LOCAL_PORT}
}
EOF
    chmod 600 "${CONFIG_FILE}"
    SHOW_BIND_QR=true
    if [[ "$DAEMON_MODE" = false ]]; then
        echo -e "${GREEN}[+] Generated persistent session keys -> ${CONFIG_FILE}${NC}\n"
    fi
fi

# Start transparent local HTTP reverse proxy on PROXY_PORT to rewrite Host to localhost:$LOCAL_PORT
PROXY_PORT=$((LOCAL_PORT + 1))
python3 -c "
import http.server, urllib.request, sys

class ThreadedHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

class ProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_ANY(self):
        auth_header = self.headers.get('Authorization', '')
        expected_token = '${TOKEN}'
        if not auth_header.startswith('Bearer ') or auth_header[7:].strip() != expected_token:
            self.send_response(401)
            self.send_header('Content-Type', 'text/plain')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(b'Unauthorized: Invalid or missing pairing token')
            return

        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len) if content_len > 0 else None
        
        target_url = f'http://127.0.0.1:${LOCAL_PORT}' + self.path
        req = urllib.request.Request(target_url, data=body, method=self.command)
        
        for k, v in self.headers.items():
            if k.lower() not in ['host', 'connection', 'content-length']:
                req.add_header(k, v)
        req.add_header('Host', f'localhost:${LOCAL_PORT}')
        
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                self.send_response(resp.status)
                for hk, hv in resp.headers.items():
                    if hk.lower() not in ['transfer-encoding', 'connection']:
                        self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(resp.read())
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            for hk, hv in e.headers.items():
                if hk.lower() not in ['transfer-encoding', 'connection']:
                    self.send_header(hk, hv)
            self.end_headers()
            self.wfile.write(e.read())
        except Exception as e:
            self.send_response(502)
            self.end_headers()
            self.wfile.write(str(e).encode())

    do_GET = do_ANY
    do_POST = do_ANY
    do_PUT = do_ANY
    do_DELETE = do_ANY
    do_PATCH = do_ANY
    do_HEAD = do_ANY

httpd = ThreadedHTTPServer(('127.0.0.1', ${PROXY_PORT}), ProxyHandler)
httpd.serve_forever()
" &
PROXY_PID=$!
echo $PROXY_PID > "${PROXY_PID_FILE}"

# Render pairing payload and Terminal QR Code if binding requested
if [[ "$SHOW_BIND_QR" = true ]] && [[ "$DAEMON_MODE" = false ]]; then
    QR_PAYLOAD=$(python3 -c "
import json, base64

data = {
    'version': 'bunker-v2',
    'ntfy_topic': '${TOPIC}',
    'key': '${KEY_B64}',
    'token': '${TOKEN}',
    'port': ${LOCAL_PORT}
}
encoded = base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip('=')
print('KANZOBUNKER:' + encoded)
")

    echo -e "${CYAN}${BOLD}======================================================================${NC}"
    echo -e "${YELLOW}${BOLD}🚀 KANZO MOBILE PAIRING CODE (FORTINET BYPASS)${NC}"
    echo -e "${CYAN}${BOLD}======================================================================${NC}"
    echo -e "📱 Scan this QR code in the Kanzo Android App (one-time setup):"
    echo "----------------------------------------------------------------------"
    python3 -c "
import qrcode
qr = qrcode.QRCode(border=1)
qr.add_data('${QR_PAYLOAD}')
qr.make(fit=True)
qr.print_ascii(invert=True)
" 2>/dev/null || echo -e "Install 'qrcode' for ASCII QR rendering: pip install qrcode"
    echo "----------------------------------------------------------------------"
    echo -e "Pairing Ticket: ${CYAN}${QR_PAYLOAD}${NC}"
    echo -e "${CYAN}${BOLD}======================================================================${NC}\n"
fi

encrypt_and_publish() {
    local target_url="$1"
    if python3 -c "
import base64, os, urllib.request, json, sys, time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

key = base64.b64decode('${KEY_B64}')
aesgcm = AESGCM(key)
nonce = os.urandom(12)
payload_dict = {
    'url': '${target_url}',
    'token': '${TOKEN}',
    'timestamp': int(time.time())
}
plaintext = json.dumps(payload_dict).encode('utf-8')
ciphertext = aesgcm.encrypt(nonce, plaintext, None)
payload_b64 = base64.b64encode(nonce + ciphertext).decode('utf-8')

# Publish to high-availability rendezvous federation
servers = ['https://ntfy.sh', 'https://ntfy.adminforge.de']
success = False
for srv in servers:
    try:
        req = urllib.request.Request(
            f'{srv}/${TOPIC}',
            data=payload_b64.encode('utf-8'),
            headers={
                'Title': 'Kanzo Tunnel Active',
                'Priority': 'high',
                'Tags': 'kanban,lock,shield'
            },
            method='POST'
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                print(f'[✓] Encrypted rendezvous endpoint published via {srv}')
                success = True
    except Exception:
        continue

sys.exit(0 if success else 1)
"; then
        return 0
    else
        echo -e "${YELLOW}[!] Rendezvous publication failed to both servers. Will retry in background.${NC}" >&2
        return 1
    fi
}

CANDIDATE_HOSTS=("ap.a.pinggy.io" "us.a.pinggy.io" "eu.a.pinggy.io" "a.pinggy.io" "free.pinggy.io")
HOST_INDEX=0

start_pinggy_tunnel() {
    local target_host="${CANDIDATE_HOSTS[$HOST_INDEX]}"
    echo -e "${BLUE}[*] Connecting to Pinggy cluster node: ${CYAN}${target_host}${NC} (port 443)..."
    # Outbound SSH over TCP port 443 with TLS masquerade
    ssh -p 443 \
        -R0:127.0.0.1:${PROXY_PORT} \
        -o StrictHostKeyChecking=accept-new \
        -o UserKnownHostsFile="${CONFIG_DIR}/known_hosts" \
        -o ServerAliveInterval=15 \
        -o ServerAliveCountMax=3 \
        -o ExitOnForwardFailure=yes \
        -o TCPKeepAlive=yes \
        -N "${target_host}" > "${LOG_FILE}" 2>&1 &
    TUNNEL_PID=$!
}

start_cloudflare_tunnel() {
    if ! command -v cloudflared &>/dev/null; then
        echo -e "${RED}[!] cloudflared is not installed. Please install cloudflared or use --provider pinggy.${NC}"
        exit 1
    fi
    cloudflared tunnel --url "http://127.0.0.1:${PROXY_PORT}" > "${LOG_FILE}" 2>&1 &
    TUNNEL_PID=$!
}

discover_pinggy_url() {
    local timeout="${1:-30}"
    local url=""
    for _ in $(seq 1 "$timeout"); do
        if [[ -f "${LOG_FILE}" ]]; then
            url=$(grep -oE "https://[a-zA-Z0-9.-]+\.(pinggy\.link|pinggy-free\.link)" "${LOG_FILE}" | tail -n 1 || true)
            if [[ -z "$url" ]]; then
                url=$(grep -oE "https://[a-zA-Z0-9.-]+" "${LOG_FILE}" | grep -v "pinggy.io" | tail -n 1 || true)
            fi
            if [[ -n "$url" ]]; then
                echo "$url"
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

discover_cloudflare_url() {
    local timeout="${1:-30}"
    local url=""
    for _ in $(seq 1 "$timeout"); do
        if [[ -f "${LOG_FILE}" ]]; then
            url=$(grep -oE "https://[a-zA-Z0-9.-]+\.trycloudflare\.com" "${LOG_FILE}" | tail -n 1 || true)
            if [[ -n "$url" ]]; then
                echo "$url"
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

ASSIGNED_URL=""
case "$TUNNEL_PROVIDER" in
    pinggy)
        echo -e "${BLUE}[*] Establishing reverse tunnel via Pinggy SSH 443 (Fortinet bypass)...${NC}"
        start_pinggy_tunnel
        echo -e "${YELLOW}[*] Waiting for Pinggy endpoint assignment on port 443...${NC}"
        ASSIGNED_URL=$(discover_pinggy_url 30 || true)
        ;;
    cloudflare)
        echo -e "${BLUE}[*] Establishing reverse tunnel via Cloudflare tunnel...${NC}"
        start_cloudflare_tunnel
        echo -e "${YELLOW}[*] Waiting for Cloudflare endpoint assignment...${NC}"
        ASSIGNED_URL=$(discover_cloudflare_url 30 || true)
        ;;
    custom)
        if [[ -z "$CUSTOM_URL" ]]; then
            echo -e "${RED}[!] --custom-url is required when --provider custom is selected.${NC}"
            exit 1
        fi
        ASSIGNED_URL="$CUSTOM_URL"
        TUNNEL_PID=""
        ;;
    *)
        echo -e "${RED}[!] Unsupported tunnel provider: '$TUNNEL_PROVIDER'. Supported: pinggy, cloudflare, custom${NC}"
        exit 1
        ;;
esac

PUBLISHED=false
if [[ -n "$ASSIGNED_URL" ]]; then
    echo -e "${GREEN}${BOLD}[✓] Tunnel is LIVE!${NC}"
    echo -e "    Provider:        ${CYAN}${TUNNEL_PROVIDER}${NC}"
    echo -e "    Public URL:      ${CYAN}${ASSIGNED_URL}${NC}"
    echo -e "    Encrypted Topic: ${CYAN}${TOPIC}${NC}"
    if encrypt_and_publish "$ASSIGNED_URL"; then
        PUBLISHED=true
        echo -e "${GREEN}[✓] Published dynamic AES-256-GCM encrypted rendezvous ticket!${NC}"
    fi
    echo -e "${BLUE}[*] Fortinet bypass active over TCP 443. Press Ctrl+C to terminate.${NC}\n"
else
    echo -e "${RED}[!] Tunnel failed to obtain public URL. Check log:${NC}"
    cat "${LOG_FILE}" 2>/dev/null || true
fi

# Tunnel supervisor loop with auto-reconnection and publication retry
RETRY_COUNT=0
while true; do
    if [[ "$TUNNEL_PROVIDER" == "pinggy" ]]; then
        if [[ -n "${TUNNEL_PID:-}" ]] && ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
            echo -e "${YELLOW}[!] Pinggy tunnel disconnected. Rotating cluster node & reconnecting outbound SSH 443...${NC}"
            HOST_INDEX=$(( (HOST_INDEX + 1) % ${#CANDIDATE_HOSTS[@]} ))
            start_pinggy_tunnel
            NEW_URL=$(discover_pinggy_url 30 || true)
            if [[ -n "$NEW_URL" ]]; then
                ASSIGNED_URL="$NEW_URL"
                echo -e "${GREEN}[✓] New Pinggy Endpoint assigned: ${ASSIGNED_URL}${NC}"
                if encrypt_and_publish "$ASSIGNED_URL"; then
                    PUBLISHED=true
                else
                    PUBLISHED=false
                fi
            fi
        fi
    fi

    # Retry publication if previous attempt failed
    if [[ "$PUBLISHED" = false ]] && [[ -n "$ASSIGNED_URL" ]]; then
        RETRY_COUNT=$((RETRY_COUNT + 1))
        if (( RETRY_COUNT % 2 == 0 )); then
            echo -e "${YELLOW}[*] Retrying rendezvous publication for ${ASSIGNED_URL}...${NC}"
            if encrypt_and_publish "$ASSIGNED_URL"; then
                PUBLISHED=true
                echo -e "${GREEN}[✓] Rendezvous publication succeeded on retry!${NC}"
            fi
        fi
    fi

    sleep 5
done
