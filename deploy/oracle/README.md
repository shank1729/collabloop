# Oracle VM deployment

The app is running on an Always Free `VM.Standard.E2.1.Micro` Ubuntu 24.04 Minimal
VM in Hyderabad at [collabloop.129-225-102-84.sslip.io](https://collabloop.129-225-102-84.sslip.io/).
The [previous address](https://collab.129-225-102-84.sslip.io/) also serves the app.
The public frontend, API health endpoint, and SockJS WebSocket connection have
been verified. Use the two-user smoke checklist below to verify signed-in collaboration.

## Layout

The gateway serves the React build and terminates HTTPS. `/api/*` is proxied to
the Spring backend with `/api` removed. `/ws` and `/ws/*` are proxied unchanged for
SockJS/STOMP. Neon remains the PostgreSQL database, preserving existing users and
documents. No database copy or replacement is part of this deployment.

Only gateway ports 80/443 are published. The backend is reachable on the private
Docker network and trusts only the gateway at `172.29.73.2` for forwarded client
addresses. Caddy ignores incoming untrusted X-Forwarded values by default. This
setup assumes users connect directly to this VM: adding a CDN or load balancer
requires a fresh trust review. Verify client-IP behavior from two networks after
launch. Check that the Docker subnet does not conflict with existing networks.

## 1. Oracle access and a free VM

Sign in to [Oracle Cloud](https://cloud.oracle.com/) yourself, including any MFA,
account verification or billing-card verification. The account owner handles
account creation and payment details.

In the home region, create a new Ubuntu 24.04 LTS instance using an Always
Free-eligible Ampere A1 Flex shape. Use 2 OCPUs / 12 GB only if the console confirms
that allocation remains inside the tenancy's available free allowance. A smaller
1 OCPU / 6 GB allocation is also suitable for this small app. Do not select a paid
shape or treat time-limited trial credits as permanent free capacity.

Oracle's documentation currently lists 1,500 A1 OCPU hours / 9,000 GB hours per
month, equivalent to 2 OCPUs / 12 GB total for an Always Free tenancy, plus 200 GB
total free block storage. Inspect the tenancy's existing usage and the final cost
estimate. Use a default-size boot volume within the remaining free storage.
Capacity can be unavailable; wait or try another eligible availability domain
instead of silently switching to a paid shape. Idle free VMs may be reclaimed.
[Oracle Always Free limits](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

Use a public subnet, internet gateway/default route and public IPv4. Restrict SSH
port 22 to your administrative source IP. Permit inbound TCP 80/443 for web users
through the subnet security list or an attached NSG, and ensure outbound HTTPS
and Neon connectivity are allowed. Inspect any host firewall separately; never
flush the firewall or remove the SSH rule to make the website reachable.

Add an SSH public key at creation and keep the private key on your computer.
Never paste the private key or a database password into the chat. Record the
public IP, OS image and SSH username (`ubuntu` for the Ubuntu image). Confirm the
VM host-key fingerprint through a trusted console before accepting it in SSH.

## 2. Hostname and HTTPS

For a free initial hostname, use `collab.PUBLIC-IP-WITH-DASHES.sslip.io`, replacing
the embedded address with this VM's actual public IPv4. `203.0.113.10` in the env
example is documentation-only and cannot host the app. The DNS provider resolves
the embedded public IP without creating an account or purchasing a domain.
[sslip.io / nip.io documentation](https://sslip.io/)

Confirm DNS returns the VM address. Port 80/443 must be reachable from the
internet for Caddy's automatic certificate management. Wait until a valid public
certificate is issued before entering login credentials. Shared free DNS and CA
rate limits can affect issuance; do not repeatedly delete certificate state or
bypass certificate warnings. A domain you own can replace this hostname later.
[Caddy HTTPS requirements](https://caddyserver.com/docs/automatic-https)

## 3. Install and transfer

Once SSH access is available, inspect the OS, running services, disks and network
rules. Install Docker Engine and its Compose plugin using the
[official Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/).
The images support building on the VM's native architecture; verify the actual
build on that VM. This package does not require Node, Java or PostgreSQL installed
on the host. Allow sufficient free RAM/disk for the Node and Maven build stages.

Transfer a reviewed source snapshot to a dedicated directory, or clone a
reviewed commit from [shank1729/collabloop](https://github.com/shank1729/collabloop).
Exclude `.git`,
local env files, SSH keys, node_modules, build output and logs from a source
archive. Do not overwrite an existing application directory without inspection.

On the VM, in `deploy/oracle/`:

```sh
umask 077
cp env.example .env
chmod 600 .env
```

Set the bare hostname, existing Neon connection string and signing key in `.env`.
Compose reads this file explicitly as deployment configuration; Spring is not
expected to load it itself. Quote env values appropriately if they contain `$`,
`#` or other dotenv syntax. Prefer the provider's URL-encoded connection string.

A new key can be generated on the VM using `openssl rand -base64 32`. To keep
existing sessions valid across hosts, reuse the existing production signing key
securely instead; changing it requires users to sign in again. Never commit keys
or print a rendered Compose configuration containing secrets into shared logs.

## 4. Build and launch

### E2 Micro: transfer prebuilt artifacts

The 1 GB E2 Micro cannot use the 2 GB backend configuration below. Use the
standalone `compose.micro.yaml` instead. Build the backend JAR and frontend on
your development machine, with `REACT_APP_API_BASE_URL=/api` and
`REACT_APP_WS_URL=/ws` for the React build so each hostname connects back to itself. Transfer the JAR as
`deploy/oracle/release/app.jar` and the React build directory contents as
`deploy/oracle/release/frontend/` on the VM. Neither artifact contains the Neon
password or signing key; those belong only in the VM's protected `.env` file.

From the VM's `deploy/oracle/` directory:

```sh
sudo docker compose --env-file .env -f compose.micro.yaml config --quiet
sudo docker compose --env-file .env -f compose.micro.yaml pull
sudo docker compose --env-file .env -f compose.micro.yaml run --rm --no-deps gateway \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo docker compose --env-file .env -f compose.micro.yaml up -d
sudo docker compose --env-file .env -f compose.micro.yaml ps
```

The backend has a 640 MB container limit, 256 MB Java heap, limited native memory,
30 HTTP worker threads and three database connections. The gateway has a 128 MB
limit. This is a small demo deployment; monitor memory and restart counts under
real collaboration traffic before relying on it. Never run Maven or npm builds
on this VM. Preserve the old artifacts for rollback when transferring updates.

### A1: build source on the VM

From `deploy/oracle/` on the VM:

```sh
sudo docker compose --env-file .env config --quiet
sudo docker compose --env-file .env build
sudo docker compose --env-file .env up -d
sudo docker compose --env-file .env ps
```

Validate Caddy configuration inside the built gateway image before launch if
needed (this command does not request certificates):

```sh
sudo docker compose --env-file .env run --rm --no-deps gateway \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

The gateway may return 502 while Spring connects to Neon and finishes starting.
Allow several minutes on the E2 Micro before deciding that a restart failed.
`docker compose ps` alone does not prove the app is ready. Verify the
public HTTPS endpoint, without disabling certificate verification:

```sh
curl --fail --show-error https://YOUR_HOST/api/actuator/health
curl --fail --show-error https://YOUR_HOST/
```

Health must report UP. Check gateway/backend logs if certificate issuance,
database access or startup fails. Keep certificates in the named Caddy volumes.

## 5. Verify and maintain

Run the two-user smoke checklist in [TESTING.md](../../TESTING.md): registration,
sign-in, document creation/rename/delete, editor/viewer roles, bidirectional edits,
presence, access removal, history and JWT expiry. Confirm browser requests use
this hostname, `/api` and `/ws` rather than localhost or the old Render backend.
Check rate-limit identity using two networks and forged forwarding headers.

Containers restart after reboot with Docker enabled. The in-memory presence and
message broker support one backend instance. Neon holds durable data; Caddy's
volumes hold certificates. Arrange provider backups and keep a secure copy of
the deployment env outside the VM, because a free VM can be reclaimed.

For updates, preserve the old source snapshot/commit and `.env`, review/test the
new source, rebuild and recreate containers. To keep two hostnames working, set
`APP_SITE_ADDRESSES` to a space-separated list of hostnames and
`APP_ALLOWED_ORIGINS` to the corresponding comma-separated HTTPS origins (see
`env.example`). Sessions are stored per hostname in the browser, so users may
need to sign in again when they switch URLs. Keep the old Render deployment as
a fallback until Oracle smoke checks pass. Do not run
`docker compose down -v` as a routine update: it removes certificate volumes.

Deploying source does not require a GitHub push. Publishing source to GitHub
is separate from updating the VM; pushing the Render deployment branch
may also trigger Render auto-deploy. No cloud resources are created by these files.
