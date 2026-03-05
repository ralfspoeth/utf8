# Fast UTF-8 Readers and Writers

A Java module which contains fast `Reader`
and `Writer` implementations for UTF-8
`InputStream`s and `OutputStream`s, respectively.

## Usage

### Project Integration



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
    try(var os = ...; wrtr = new Utf8Writer(os)) {
        wrtr.write(buffer);
    }
```
