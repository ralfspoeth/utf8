# Fast UTF-8 Readers and Writers

A Java module which contains fast `Reader`
and `Writer` implementations for UTF-8
`InputStream`s and `OutputStream`s, respectively.
It uses code borrowed from _Björn Höhrmann_'s
branchless DFA-based UTF-8 decoder.

## Usage

### Project Integration

Use these maven coordinates to incorporate the library in your
work:

    groupId: io.github.ralfspoeth
    artefactId: utf8
    version: 1.0.3

The library requires JDK 21 or later.

### Code Examples

Reading UTF-8 input streams works as
```java
    try(var is = ...; var rdr = new Utf8Reader(is)) {
        int c = -1;
        while((c=rdr.read())!=-1) {
            char ch = (char)c;
            //
        }
    }
```

and writing text into output streams
```java
    char[] buffer;
    try(var os = ...; var wrtr = new Utf8Writer(os)) {
        wrtr.write(buffer);
    }
```
That's it.
