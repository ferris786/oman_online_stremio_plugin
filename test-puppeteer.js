#!/usr/bin/env node
/**
 * Test script for Puppeteer m3u8 extraction
 * 
 * Usage:
 *   node test-puppeteer.js <kayihome-url>
 *   
 * Examples:
 *   node test-puppeteer.js "https://kayihome.xyz/player/?data=abc123"
 *   DEBUG=true node test-puppeteer.js "https://kayihome.xyz/player/?data=abc123"
 */

const { extractM3U8FromKayihome } = require('./src/puppeteer-extractor');

async function main() {
    const url = process.argv[2];
    
    if (!url) {
        console.log('Usage: node test-puppeteer.js <kayihome-url>');
        console.log('');
        console.log('Environment variables:');
        console.log('  DEBUG=true           Enable debug logging');
        console.log('  USE_PUPPETEER=true   Enable Puppeteer fallback in kayifamily');
        console.log('');
        console.log('Examples:');
        console.log('  node test-puppeteer.js "https://kayihome.xyz/player/?data=abc123"');
        console.log('  DEBUG=true node test-puppeteer.js "https://kayihome.xyz/player/?data=abc123"');
        process.exit(1);
    }

    console.log('========================================');
    console.log('Puppeteer M3U8 Extraction Test');
    console.log('========================================');
    console.log('');
    console.log('Target URL:', url);
    console.log('Debug mode:', process.env.DEBUG === 'true' ? 'ON' : 'OFF');
    console.log('Environment:', process.env.RENDER ? 'Render' : 'Local');
    console.log('');
    console.log('Starting extraction...');
    console.log('----------------------------------------');

    const startTime = Date.now();
    
    try {
        const result = await extractM3U8FromKayihome(url, {
            timeout: 45000,
            headless: process.env.HEADLESS !== 'false', // Default to headless
            environment: process.env.RENDER ? 'render' : 'default'
        });

        const duration = ((Date.now() - startTime) / 1000).toFixed(2);

        console.log('----------------------------------------');
        console.log(`Extraction completed in ${duration}s`);
        console.log('');

        if (result) {
            console.log('✓ SUCCESS! M3U8 URL extracted:');
            console.log('');
            console.log(result);
            console.log('');
            console.log('========================================');
            process.exit(0);
        } else {
            console.log('✗ FAILED: No M3U8 URL found');
            console.log('');
            console.log('Possible reasons:');
            console.log('- The page requires authentication');
            console.log('- The player uses a different format (MP4, WebM)');
            console.log('- The detection is blocking Puppeteer');
            console.log('- The URL is invalid or expired');
            console.log('');
            console.log('Try running with DEBUG=true for more information');
            console.log('========================================');
            process.exit(1);
        }

    } catch (error) {
        const duration = ((Date.now() - startTime) / 1000).toFixed(2);
        console.log('----------------------------------------');
        console.log(`Extraction failed after ${duration}s`);
        console.log('');
        console.log('✗ ERROR:', error.message);
        console.log('');
        console.log('Stack trace:');
        console.log(error.stack);
        console.log('');
        console.log('========================================');
        process.exit(1);
    }
}

main();
