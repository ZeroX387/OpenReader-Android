# Third-party notices

## MOBI/AZW3 parser

The Kotlin MOBI/KF8 parser under `app/src/main/java/com/openreader/mobi/` is adapted from the open-source project [ChengqianHuang/Reader](https://github.com/ChengqianHuang/Reader), Copyright (c) 2026 huangchengqian, under the MIT License.

MIT License

Copyright (c) 2026 huangchengqian

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


## Apache POI (legacy Microsoft Word DOC)

OpenReader uses Apache POI 5.5.1 `poi-scratchpad` / HWPF to extract text from legacy Word 97–2003 `.doc` files. Apache POI is developed by the Apache Software Foundation and is licensed under the Apache License, Version 2.0.

- Project: https://poi.apache.org/
- License: https://www.apache.org/licenses/LICENSE-2.0
- Component documentation: https://poi.apache.org/components/document/index.html

HWPF is intended here for text extraction only; document layout, embedded objects, and some older or protected Word variants may not be preserved or supported.
