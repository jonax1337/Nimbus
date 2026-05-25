import { type NextRequest } from "next/server";

const HEADER_CONTROLLER_URL = "x-nimbus-controller";
const HEADER_CONTROLLER_TOKEN = "x-nimbus-token";

/**
 * Server-side proxy for Nimbus controller API.
 *
 * The browser sends requests to /api/proxy/<path> with two custom headers:
 *   X-Nimbus-Controller: <controller base URL, e.g. http://152.53.124.143:8080>
 *   X-Nimbus-Token: <API bearer token>
 *
 * This route strips those headers, forwards the request to the controller
 * over plain HTTP (server-to-server, no mixed-content issue), and streams
 * the response back to the browser over HTTPS.
 */
async function proxyRequest(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const controllerUrl = request.headers.get(HEADER_CONTROLLER_URL);
  const controllerToken = request.headers.get(HEADER_CONTROLLER_TOKEN);
  const inboundAuth = request.headers.get("authorization");

  if (!controllerUrl) {
    return Response.json(
      { error: "Missing X-Nimbus-Controller header" },
      { status: 400 }
    );
  }

  const { path } = await params;
  const targetPath = "/" + path.join("/");
  const search = request.nextUrl.search;
  const targetUrl = `${controllerUrl.replace(/\/+$/, "")}${targetPath}${search}`;

  const headers = new Headers();
  if (controllerToken) {
    headers.set("Authorization", `Bearer ${controllerToken}`);
  } else if (inboundAuth) {
    // Login flow uses controllerFetch which puts the token in Authorization
    // before the X-Nimbus-Token convention is established. Pass it through.
    headers.set("Authorization", inboundAuth);
  }

  const contentType = request.headers.get("content-type");
  if (contentType) {
    headers.set("Content-Type", contentType);
  }

  // Buffer the body for non-GET methods. Piping request.body as a ReadableStream
  // is unreliable in Next.js App Router — the stream may already be consumed or
  // locked, causing write requests (PUT/POST/PATCH/DELETE) to send empty bodies.
  const hasBody = !["GET", "HEAD", "OPTIONS"].includes(request.method);
  let body: BodyInit | null = null;
  if (hasBody) {
    const buf = await request.arrayBuffer();
    if (buf.byteLength > 0) {
      body = buf;
      headers.set("Content-Length", buf.byteLength.toString());
    }
  }

  try {
    const upstream = await fetch(targetUrl, {
      method: request.method,
      headers,
      body,
    });

    const responseHeaders = new Headers();
    upstream.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      if (
        lower !== "transfer-encoding" &&
        lower !== "connection" &&
        lower !== "keep-alive"
      ) {
        responseHeaders.set(key, value);
      }
    });

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch (err) {
    const message =
      err instanceof Error ? err.message : "Failed to reach controller";
    return Response.json({ error: message }, { status: 502 });
  }
}

export const GET = proxyRequest;
export const POST = proxyRequest;
export const PUT = proxyRequest;
export const PATCH = proxyRequest;
export const DELETE = proxyRequest;
export const HEAD = proxyRequest;
export const OPTIONS = proxyRequest;
