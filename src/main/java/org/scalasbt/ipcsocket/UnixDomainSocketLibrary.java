/*

Copyright 2004-2015, Martian Software, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/
package org.scalasbt.ipcsocket;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Utility class to bridge native Unix domain socket calls to Java using JNA. */
public class UnixDomainSocketLibrary {
  public static final int PF_LOCAL = 1;
  public static final int AF_LOCAL = 1;
  public static final int SOCK_STREAM = 1;

  public static final int SHUT_RD = 0;
  public static final int SHUT_WR = 1;

  // Utility class, do not instantiate.
  private UnixDomainSocketLibrary() {}

  // BSD platforms write a length byte at the start of struct sockaddr_un.
  private static final boolean HAS_SUN_LEN =
      Platform.isMac()
          || Platform.isFreeBSD()
          || Platform.isNetBSD()
          || Platform.isOpenBSD()
          || Platform.iskFreeBSD();

  /** Bridges {@code struct sockaddr_un} to and from native code. */
  public static class SockaddrUn extends Structure implements Structure.ByReference {
    /**
     * On BSD platforms, the {@code sun_len} and {@code sun_family} values in {@code struct
     * sockaddr_un}.
     */
    public static class SunLenAndFamily extends Structure {
      public byte sunLen;
      public byte sunFamily;

      protected List getFieldOrder() {
        return Arrays.asList(new String[] {"sunLen", "sunFamily"});
      }
    }

    /**
     * On BSD platforms, {@code sunLenAndFamily} will be present. On other platforms, only {@code
     * sunFamily} will be present.
     */
    public static class SunFamily extends Union {
      public SunLenAndFamily sunLenAndFamily;
      public short sunFamily;
    }

    public SunFamily sunFamily = new SunFamily();
    public byte[] sunPath = new byte[104];

    /** Constructs an empty {@code struct sockaddr_un}. */
    public SockaddrUn() {
      if (HAS_SUN_LEN) {
        sunFamily.sunLenAndFamily = new SunLenAndFamily();
        sunFamily.setType(SunLenAndFamily.class);
      } else {
        sunFamily.setType(Short.TYPE);
      }
      allocateMemory();
    }

    /**
     * Constructs a {@code struct sockaddr_un} with a path whose bytes are encoded using the default
     * encoding of the platform.
     */
    public SockaddrUn(String path) throws IOException {
      byte[] pathBytes = path.getBytes();
      if (pathBytes.length > sunPath.length - 1) {
        throw new IOException(
            "Cannot fit name [" + path + "] in maximum unix domain socket length");
      }
      System.arraycopy(pathBytes, 0, sunPath, 0, pathBytes.length);
      sunPath[pathBytes.length] = (byte) 0;
      if (HAS_SUN_LEN) {
        int len = fieldOffset("sunPath") + pathBytes.length;
        sunFamily.sunLenAndFamily = new SunLenAndFamily();
        sunFamily.sunLenAndFamily.sunLen = (byte) len;
        sunFamily.sunLenAndFamily.sunFamily = AF_LOCAL;
        sunFamily.setType(SunLenAndFamily.class);
      } else {
        sunFamily.sunFamily = AF_LOCAL;
        sunFamily.setType(Short.TYPE);
      }
      allocateMemory();
    }

    protected List getFieldOrder() {
      return Arrays.asList(new String[] {"sunFamily", "sunPath"});
    }
  }

  static {
    Native.register(Platform.C_LIBRARY_NAME);
  }

  public static native int socket(int domain, int type, int protocol) throws LastErrorException;

  public static native int bind(int fd, SockaddrUn address, int addressLen)
      throws LastErrorException;

  public static native int listen(int fd, int backlog) throws LastErrorException;

  public static native int accept(int fd, SockaddrUn address, IntByReference addressLen)
      throws LastErrorException;

  public static native int connect(int fd, SockaddrUn address, int addressLen)
      throws LastErrorException;

  public static native int read(int fd, ByteBuffer buffer, int count) throws LastErrorException;

  public static native int write(int fd, ByteBuffer buffer, int count) throws LastErrorException;

  public static native int close(int fd) throws LastErrorException;

  public static native int shutdown(int fd, int how) throws LastErrorException;
}

class JNAUnixDomainSocketLibraryProvider implements UnixDomainSocketLibraryProvider {
  private static final JNAUnixDomainSocketLibraryProvider instance =
      new JNAUnixDomainSocketLibraryProvider();

  static final JNAUnixDomainSocketLibraryProvider instance() {
    return instance;
  }

  @Override
  public int socket(int domain, int type, int protocol) throws NativeErrorException {
    try {
      return UnixDomainSocketLibrary.socket(domain, type, protocol);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int bind(int fd, byte[] address, int addressLen) throws NativeErrorException {
    try {
      final UnixDomainSocketLibrary.SockaddrUn sockaddrUn =
          new UnixDomainSocketLibrary.SockaddrUn(new String(address));
      return UnixDomainSocketLibrary.bind(fd, sockaddrUn, sockaddrUn.size());
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    } catch (final IOException e) {
      throw new NativeErrorException(0, e.getMessage());
    }
  }

  @Override
  public int listen(int fd, int backlog) throws NativeErrorException {
    try {
      final UnixDomainSocketLibrary.SockaddrUn sockaddrUn =
          new UnixDomainSocketLibrary.SockaddrUn();
      return UnixDomainSocketLibrary.listen(fd, backlog);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int accept(int fd) throws NativeErrorException {
    try {
      final UnixDomainSocketLibrary.SockaddrUn sockaddrUn =
          new UnixDomainSocketLibrary.SockaddrUn();
      IntByReference addressLen = new IntByReference();
      addressLen.setValue(sockaddrUn.size());
      return UnixDomainSocketLibrary.accept(fd, sockaddrUn, addressLen);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int connect(int fd, byte[] address, int len) throws NativeErrorException {
    try {
      final UnixDomainSocketLibrary.SockaddrUn sockaddrUn =
          new UnixDomainSocketLibrary.SockaddrUn(new String(address));
      return UnixDomainSocketLibrary.connect(fd, sockaddrUn, sockaddrUn.size());
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    } catch (final IOException e) {
      throw new NativeErrorException(-1, e.getMessage());
    }
  }

  @Override
  public int read(int fd, byte[] buffer, int offset, int len) throws NativeErrorException {
    try {
      if (offset > buffer.length - 1) {
        String message = "offset: " + offset + " greater than buffer size " + buffer.length;
        throw new IllegalArgumentException(message);
      }
      if (offset + len > buffer.length) {
        String message =
            "Tried to read more bytes "
                + len
                + " than available from position "
                + offset
                + " in buffer of size "
                + buffer.length;
        throw new IllegalArgumentException(message);
      }
      return UnixDomainSocketLibrary.read(fd, ByteBuffer.wrap(buffer, offset, len), len);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int write(int fd, byte[] buffer, int offset, int len) throws NativeErrorException {
    try {
      return UnixDomainSocketLibrary.write(fd, ByteBuffer.wrap(buffer, offset, len), len);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int close(int fd) throws NativeErrorException {
    try {
      return UnixDomainSocketLibrary.close(fd);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }

  @Override
  public int shutdown(int fd, int how) throws NativeErrorException {
    try {
      return UnixDomainSocketLibrary.shutdown(fd, how);
    } catch (final LastErrorException e) {
      throw new NativeErrorException(e.getErrorCode(), e.getMessage());
    }
  }
}
