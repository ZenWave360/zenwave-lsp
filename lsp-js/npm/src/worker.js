// Web Worker entry point of @zenwave360/lsp-js: the ZenWave language server inside a browser Web Worker.
//
// Transport: the worker's postMessage channel, through vscode-languageserver/browser. The client creates
// `new Worker(<url of this file>)` and connects with BrowserMessageReader/BrowserMessageWriter on it.
// No Node API is used; the bundle is self-contained.
import { BrowserMessageReader, BrowserMessageWriter, createConnection, ResponseError } from 'vscode-languageserver/browser';
import { startZenwaveLanguageServer } from 'zenwave-lsp-kotlin';

const messageReader = new BrowserMessageReader(self);
const messageWriter = new BrowserMessageWriter(self);

startZenwaveLanguageServer(
  createConnection(messageReader, messageWriter),
  (code, message, data) => new ResponseError(code, message, data),
);
