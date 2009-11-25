#include "frame.h"
#include <stdio.h>
#include "SDL.h"

int main(int argc, char** argv)
{
	if(argc < 2) {
		fprintf(stderr, "usage: %s <filename>\n", argv[0]);
		return 1;
	}
	struct frame_input_stream* in = fis_open(argv[1]);
	struct frame* frame;
	int prev_width = -1;
	int prev_height = -1;

	if(SDL_Init(SDL_INIT_VIDEO) < 0) {
		fprintf(stderr, "Can't initialize SDL.\n");
		return 1;
	}

	int rbyte, gbyte, bbyte;

	SDL_Surface* surf;

	while((frame = fis_next_frame(in))) {
		if(prev_width != frame->f_width || prev_height != frame->f_height)
			surf = SDL_SetVideoMode(frame->f_width, frame->f_height, 32, SDL_SWSURFACE | SDL_DOUBLEBUF);
		prev_width = frame->f_width;
		prev_height = frame->f_height;
		SDL_LockSurface(surf);
		uint32_t* data = surf->pixels;
		for(uint32_t y = 0; y < frame->f_height; y++) {
			for(uint32_t x = 0; x < frame->f_width; x++) {
				uint32_t v = 0;
				v += (((frame->f_framedata[y * frame->f_width + x] >> 16) & 0xFF)
					<< surf->format->Rshift);
				v += (((frame->f_framedata[y * frame->f_width + x] >> 8) & 0xFF)
					<< surf->format->Gshift);
				v += (((frame->f_framedata[y * frame->f_width + x]) & 0xFF)
					<< surf->format->Bshift);

				data[y * surf->pitch / 4 + x] = v;
			}
		}
		SDL_UnlockSurface(surf);
		SDL_Flip(surf);
		frame_release(frame);
	}

	fis_close(in);
	SDL_Quit();
	return 0;
}