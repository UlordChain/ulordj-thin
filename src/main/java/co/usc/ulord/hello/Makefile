CC  	= gcc
CXX	= g++
CFLAGS  = -std=c99 -fopenmp -O3 -g
CXXFLAGS= -fopenmp -O3 -g
INCLUDEFLAGS = -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux"
LDFLAGS = -fopenmp -lssl -lcrypto
SRC_DIR = $(PWD)
OBJS    = PoW.o hmac_md5.o aes128.o camellia128.o crc32.o gost.o ripemd160.o sha1.o sha3_256.o skein512_256.o blake2s256.o des.o haval5_256.o rc4.o sha256.o sha512.o whirlpool.o jtr_gost.o jtr_skein.o jtr_crc32.o jtr_haval.o blake2s.o keccak1600.o my_time.o common.o PoW.o oneWayFunction.o co_usc_ulord_hello_CryptoHelloContext.o
LIB_FILE = libCryptoHello.so
TARGETS = PoW $(LIB_FILE)

.PHONY:all
all : $(TARGETS)

PoW:main.o $(OBJS)
	$(CXX) -o $@ $^ $(LDFLAGS) 
	# rm *.d *.o 

libCryptoHello.so: $(OBJS)
	gcc -shared -o  $@ $^ -lssl -lcrypto
	sudo cp $(LIB_FILE) /usr/lib
	sudo ldconfig
%.o:%.c
	$(CC) $(CFLAGS) -o $@ -fPIC -c $< $(INCLUDEFLAGS) 

%.d:%.c
	@set -e; rm -f $@; $(CC) -MM $< $(INCLUDEFLAGS) > $@.$$$$; \
	sed 's,\($*\)\.o[ :]*,\1.o $@ : ,g' < $@.$$$$ > $@; \
	rm -f $@.$$$$

-include $(OBJS:.o=.d)

.PHONY:clean 
clean:
	rm -f $(TARGETS) *.o *.d *.d.*
