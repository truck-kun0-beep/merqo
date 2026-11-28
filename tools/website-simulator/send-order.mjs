#!/usr/bin/env node
import { createHmac, randomUUID } from "node:crypto";

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i++) {
    const token = argv[i];
    if (token.startsWith("--")) {
      const key = token.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith("--")) {
        args[key] = "true";
      } else {
        args[key] = next;
        i++;
      }
    }
  }
  return args;
}

const args = parseArgs(process.argv);
const baseUrl = (args.url || process.env.MERQO_API_URL || "http://localhost:8080").replace(/\/+$/, "");
const keyId = args.key || process.env.MERQO_INTEGRATION_KEY || "mk_demo_webshop";
const secret = args.secret || process.env.MERQO_INTEGRATION_SECRET || "ms_demo_webshop_local_only_secret_2026";
const sku = args.sku || "TSHIRT-BLK-M";
const qty = Number(args.qty || "1");
const unitPrice = args.price || "550.00";
const shippingFee = args.shipping || "60.00";
const paymentMode = (args["payment-mode"] || "COD").toUpperCase();
const externalOrderId = args["ext-id"] || `SIM-${randomUUID().slice(0, 8)}`;

const subtotal = Number(unitPrice) * qty;
const total = (subtotal + Number(shippingFee)).toFixed(2);
let advanceAmount = "0.00";
let codAmount = total;
if (paymentMode === "FULL_PREPAID") {
  advanceAmount = total;
  codAmount = "0.00";
} else if (paymentMode === "ADVANCE_COD") {
  advanceAmount = args.advance || (Number(total) / 2).toFixed(2);
  codAmount = (Number(total) - Number(advanceAmount)).toFixed(2);
}

const payloadObj = {
  externalOrderId,
  customerName: args.name || "Simulated Buyer",
  phone: args.phone || "01711223344",
  address: args.address || "House 14, Road 5, Dhanmondi, Dhaka",
  district: args.district || "Dhaka",
  lines: [{ sku, quantity: qty, unitPrice: Number(unitPrice).toFixed(2) }],
  shippingFee: Number(shippingFee).toFixed(2),
  discountTotal: "0.00",
  total,
  paymentMode,
  advanceAmount,
  codAmount,
};

if (args["claimed-method"]) {
  payloadObj.claimedAdvanceMethod = args["claimed-method"];
}
if (args["claimed-ref"]) {
  payloadObj.claimedAdvanceReference = args["claimed-ref"];
}

const rawBody = JSON.stringify(payloadObj);

async function sendOnce() {
  const ts = Math.floor(Date.now() / 1000).toString();
  const signature = createHmac("sha256", secret).update(`${ts}.${rawBody}`, "utf8").digest("hex");

  const res = await fetch(`${baseUrl}/integration/v1/orders`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Merqo-Key": keyId,
      "X-Merqo-Timestamp": ts,
      "X-Merqo-Signature": signature,
    },
    body: rawBody,
  });
  const text = await res.text();
  console.log(`POST /integration/v1/orders -> ${res.status}`);
  console.log(text);
  return res.status;
}

const status = await sendOnce();
if (args.replay === "true") {
  console.log("--- Sending idempotent replay ---");
  await sendOnce();
}
if (status >= 500) {
  process.exit(1);
}
