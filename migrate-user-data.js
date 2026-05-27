#!/usr/bin/env node
/**
 * One-off migration: copies flat Firebase paths to /users/{uid}/...
 *
 * Usage:
 *   FIREBASE_DATABASE_URL=https://your-db.firebaseio.com \
 *   TARGET_UID=abc123 \
 *   node migrate-user-data.js
 */

'use strict';
const fs     = require('fs');
const crypto = require('crypto');
const https  = require('https');

const DB_URL    = process.env.FIREBASE_DATABASE_URL;
const TARGET_UID = process.env.TARGET_UID;
const SA_PATH   = process.env.SA_PATH || './firebase-service-account.json';

if (!DB_URL)     { console.error('FIREBASE_DATABASE_URL is required'); process.exit(1); }
if (!TARGET_UID) { console.error('TARGET_UID is required'); process.exit(1); }

const sa = JSON.parse(fs.readFileSync(SA_PATH, 'utf8'));

// ── JWT / OAuth2 ──────────────────────────────────────────────────────────────

function b64url(buf) {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function makeJwt() {
  const now = Math.floor(Date.now() / 1000);
  const header  = b64url(Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const payload = b64url(Buffer.from(JSON.stringify({
    iss: sa.client_email,
    sub: sa.client_email,
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
    scope: 'https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/userinfo.email',
  })));
  const sig = b64url(crypto.sign('sha256', Buffer.from(`${header}.${payload}`),
    { key: sa.private_key, padding: crypto.constants.RSA_PKCS1_PADDING }));
  return `${header}.${payload}.${sig}`;
}

function post(url, body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(body);
    const u = new URL(url);
    const req = https.request({
      hostname: u.hostname, path: u.pathname + u.search,
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': data.length },
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => resolve(JSON.parse(raw)));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function firebaseRequest(method, path, token, body) {
  return new Promise((resolve, reject) => {
    const base = DB_URL.replace(/\/$/, '');
    const url  = `${base}${path}.json?access_token=${token}`;
    const u    = new URL(url);
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const req  = https.request({
      hostname: u.hostname, path: u.pathname + u.search,
      method,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {},
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        const parsed = (raw === 'null' || raw === '') ? null : JSON.parse(raw);
        resolve(parsed);
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

function firebaseRequestVerbose(method, path, token, body) {
  return new Promise((resolve, reject) => {
    const base = DB_URL.replace(/\/$/, '');
    const url  = `${base}${path}.json?access_token=${token}`;
    const u    = new URL(url);
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const req  = https.request({
      hostname: u.hostname, path: u.pathname + u.search,
      method,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {},
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`HTTP ${res.statusCode} for ${method} ${path}: ${raw.slice(0, 200)}`));
        } else {
          resolve((raw === 'null' || raw === '') ? null : JSON.parse(raw));
        }
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// ── Migration ─────────────────────────────────────────────────────────────────

const COLLECTIONS = ['seen', 'watchlist', 'dismissed', 'tracking', 'watchedSeasons'];

async function main() {
  console.log('Getting OAuth2 token…');
  const jwt = makeJwt();
  const tokenRes = await post('https://oauth2.googleapis.com/token',
    `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`);
  if (!tokenRes.access_token) {
    console.error('Failed to get token:', tokenRes);
    process.exit(1);
  }
  const token = tokenRes.access_token;

  console.log(`Checking if /users/${TARGET_UID} already has data…`);
  const existing = await firebaseRequest('GET', `/users/${TARGET_UID}`, token);
  if (existing) {
    console.log('User path already has data — will overwrite with fresh migration.');
  }

  console.log('Reading old flat paths…');
  const results = await Promise.all(
    COLLECTIONS.map(c => firebaseRequest('GET', `/${c}`, token).then(v => ({ c, v })))
  );

  const toWrite = results.filter(r => r.v !== null);
  if (toWrite.length === 0) {
    console.log('No data found at old flat paths. Nothing to migrate.');
    process.exit(0);
  }

  console.log('Found data in:', toWrite.map(r => r.c).join(', '));
  console.log(`Writing to /users/${TARGET_UID}/…`);

  for (const { c, v } of toWrite) {
    await firebaseRequestVerbose('PUT', `/users/${TARGET_UID}/${c}`, token, v);
    console.log(`  ✓ /users/${TARGET_UID}/${c} — ${Object.keys(v).length} entries`);
  }

  console.log('\nMigration complete. The old flat paths still exist in Firebase — you can');
  console.log('delete them manually in the Firebase console once you\'ve verified everything.');
}

main().catch(err => { console.error(err); process.exit(1); });
