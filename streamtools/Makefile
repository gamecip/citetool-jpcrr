all:  screenshot.exe dumppackets.exe picturestodump.exe audiotodump.exe demuxdump.exe muxdump.exe mknulldump.exe cutdump.exe fmtopcm.exe playdump.exe dumpconvert.exe guessresolution.exe testresizer.exe dumpresolution.exe

COMPILER=g++
SDLCONFIG=sdl-config
USER_CXXFLAGS=
CXXFLAGS=-g -O2 -Wall -I. -std=gnu++0x -mno-mmx $(USER_CXXFLAGS)
CXXFLAGS2=$(CXXFLAGS) -Werror
RESIZE_DRIVERS=resize.o $(patsubst %.cpp,%.o,$(wildcard rescalers/*.cpp)) dynamic/dynamic.o
OUTPUT_DRIVERS=rgbtorgb.o $(RESIZE_DRIVERS) dedup.o $(patsubst %.cpp,%.o,$(wildcard outputs/*.cpp))
ifdef WITH_HQX
ADD_LIBS += -lhqx
CXXFLAGS2 += -DWITH_HQX=1
endif
ifdef LIBS_PATH
LIBS_PATH2=-L$(LIBS_PATH)
else
LIBS_PATH2=
endif
LIBS=$(LIBS_PATH2) -lz -lSDL -lSDL_image -lSDL_ttf -lSDL $(ADD_LIBS)
ifndef NO_SDL_SEARCH
SDL_CLFAGS=$(shell $(SDLCONFIG) --cflags)
else
SDL_CLFAGS=
endif

picturestodump.exe: picturestodump.o newpacket.o png-out.o timecounter.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

audiotodump.exe: audiotodump.o newpacket.o timecounter.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

demuxdump.exe: demuxdump.o newpacket.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

muxdump.exe: muxdump.o newpacket.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

fmtopcm.exe: fmtopcm.o newpacket.o resampler.o timecounter.o digital-filter.o opl.o misc.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

screenshot.exe: screenshot.o newpacket.o png-out.o timeparse.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

dumppackets.exe: dumppackets.o newpacket.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

mknulldump.exe: mknulldump.o newpacket.o timeparse.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

cutdump.exe: cutdump.o newpacket.o timeparse.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

dumpresolution.exe: dumpresolution.o newpacket.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

guessresolution.exe: guessresolution.o newpacket.o main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

playdump.exe: playdump.o newpacket.o timecounter.o resampler.o digital-filter.o opl.o misc.o hardsubs.o timeparse.o main.o $(RESIZE_DRIVERS)
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

dumpconvert.exe: dumpconvert.o $(OUTPUT_DRIVERS) packet-processor.o resampler.o digital-filter.o misc.o opl.o timecounter.o hardsubs.o newpacket.o timeparse.o main.o framerate-reducer.o temporal-antialias.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

rgbtorgb.o: rgbtorgb.cc lanczos.hh rgbtorgb.hh quantize.hh simd.hh
	$(COMPILER) $(CXXFLAGS) -c -o $@ $<

%.o: %.cpp newpacket.hpp
	$(COMPILER) $(CXXFLAGS2) -Werror -c -o $@ $< $(SDL_CLFAGS)

opl.o: opl.cpp opl.h
	$(COMPILER) $(CXXFLAGS2) -DOPL_INLINE=inline -c -o $@ $<

testresizer.exe: testresizer.o $(RESIZE_DRIVERS)  main.o
	$(COMPILER) $(CXXFLAGS2) -o $@ $^ $(LIBS)

resizer/%.o: resizer/*.cpp
	$(COMPILER) $(CXXFLAGS2) -Werror -c -o $@ $< $(SDL_CLFAGS)

output-drv/%.o: output-drv/*.cpp
	$(COMPILER) $(CXXFLAGS2) -Werror -c -o $@ $< `sdl-config --cflags`
