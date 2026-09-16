// Node entry point of @zenwave360/lsp-js: the ZenWave language server in a Node child process.
//
// Transport: child-process IPC by default (fork this file, as vscode-languageclient's TransportKind.ipc
// does, which also passes --node-ipc). --stdio, --socket=<port> and --pipe=<name> are honoured too.
import {
  createConnection,
  IPCMessageReader,
  IPCMessageWriter,
  ProposedFeatures,
  ResponseError,
} from 'vscode-languageserver/node';
import { startZenwaveLanguageServer } from 'zenwave-lsp-kotlin';

const transportArguments = ['--node-ipc', '--stdio'];
const hasTransportArgument = process.argv.some(
  (argument) => transportArguments.includes(argument) || argument.startsWith('--socket=') || argument.startsWith('--pipe='),
);

let connection;
if (hasTransportArgument) {
  connection = createConnection(ProposedFeatures.all);
} else if (typeof process.send === 'function') {
  connection = createConnection(ProposedFeatures.all, new IPCMessageReader(process), new IPCMessageWriter(process));
} else {
  connection = createConnection(ProposedFeatures.all, process.stdin, process.stdout);
}

startZenwaveLanguageServer(connection, (code, message, data) => new ResponseError(code, message, data));
