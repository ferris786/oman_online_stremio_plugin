const { getStream } = require('../src/stream');
const funcStr = getStream.toString();
const match = funcStr.match(/const domains = \[(.*?)\]/s);
if (match) {
    console.log("Domains Found in Memory:");
    console.log(match[1]);
} else {
    console.log("Domains array NOT found in function string.");
    console.log(funcStr.substring(0, 500)); // Print start of function
}
