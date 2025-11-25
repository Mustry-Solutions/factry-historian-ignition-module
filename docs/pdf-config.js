const fs = require('fs');
const path = require('path');

// Read logo and convert to base64
const logoPath = path.join(__dirname, 'OriginalDark.jpg');
const logoBase64 = fs.readFileSync(logoPath).toString('base64');

module.exports = {
  basedir: __dirname,
  body_class: 'markdown-body',
  css: `
    .markdown-body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
      font-size: 12px;
      line-height: 1.6;
      padding: 20px;
    }
    .markdown-body h1 {
      border-bottom: 1px solid #eaecef;
      padding-bottom: 0.3em;
    }
    .markdown-body h2 {
      border-bottom: 1px solid #eaecef;
      padding-bottom: 0.3em;
    }
    .markdown-body code {
      background-color: #f6f8fa;
      padding: 0.2em 0.4em;
      border-radius: 3px;
      font-size: 85%;
    }
    .markdown-body pre {
      background-color: #f6f8fa;
      padding: 16px;
      border-radius: 6px;
      overflow: auto;
    }
    .markdown-body img {
      max-width: 100%;
    }
    /* Cover page styles */
    .cover-page {
      page-break-after: always;
      height: calc(100vh - 160px);
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      padding: 0;
      margin: 0;
    }
    .cover-logo-top {
      height: 60px;
    }
    .cover-center {
      text-align: center;
      flex-grow: 1;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
    }
    .cover-logo-large {
      height: 80px;
      margin-bottom: 40px;
    }
    .cover-title {
      font-size: 28px;
      font-weight: bold;
      color: #1a1a2e;
      margin: 0;
      border: none !important;
    }
    .cover-footer {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      color: #666;
    }
    .cover-footer-left, .cover-footer-right {
      line-height: 1.6;
    }
    .cover-footer-right {
      text-align: right;
    }
    .cover-footer a {
      color: #4a90d9;
    }
  `,
  pdf_options: {
    format: 'A4',
    margin: {
      top: '80px',
      bottom: '80px',
      left: '40px',
      right: '40px'
    },
    displayHeaderFooter: true,
    headerTemplate: `
      <div style="width: 100%; font-size: 10px; padding: 10px 60px; box-sizing: border-box;">
        <img src="data:image/jpeg;base64,${logoBase64}" style="height: 35px;" />
      </div>
    `,
    footerTemplate: `
      <div style="width: 100%; font-size: 9px; padding: 10px 60px; box-sizing: border-box; color: #666; display: flex; justify-content: space-between;">
        <div style="line-height: 1.4;">
          <div style="color: #333;">Mustry Solutions BV</div>
          <div>Dreef Ter Elst 20</div>
          <div>8560 Gullegem</div>
        </div>
        <div style="text-align: right; line-height: 1.4;">
          <div>+324 83 70 56 97</div>
          <div><a href="mailto:info@mustrysolutions.com" style="color: #4a90d9;">info@mustrysolutions.com</a></div>
        </div>
      </div>
    `
  }
}
