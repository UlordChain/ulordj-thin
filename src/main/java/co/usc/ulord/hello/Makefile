CC  	= gcc
CXX	= g++
CFLAGS  = -std=c99 -fopenmp -O3 -g
CXXFLAGS= -fopenmp -O3 -g
INCLUDEFLAGS = -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux"
LDFLAGS = -fopenmp -lssl -lcrypto
SRC_DIR = $(PWD)
OBJS    = PoW.o c_hmac_md5.o c_aes128.o c_camellia128.o c_crc32.o c_gost.o c_ripemd160.o c_sha1.o c_sha3_256.o c_skein512_256.o c_blake2s256.o c_des.o c_haval5_256.o c_rc4.o c_sha256.o c_sha512.o c_whirlpool.o jtr_gost.o jtr_skein.o jtr_crc32.o jtr_haval.o blake2s.o keccak1600.o my_time.o common.o PoW.o oneWayFunction.o co_usc_ulord_hello_CryptoHelloContext.o
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
