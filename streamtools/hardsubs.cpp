#include "hardsubs.hpp"
#include "misc.hpp"
#include <stdexcept>
#include <iomanip>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include "timeparse.hpp"
#include "SDL_ttf.h"
#include "SDL.h"

namespace
{

	uint32_t resolution_w = 0;
	uint32_t resolution_h = 0;

	std::map<unsigned char, std::string> variables;

	void check_in_bounds(struct subtitle& sub)
	{
		bool oob = false;
		int xalign_type = sub.xalign_type;
		int yalign_type = sub.yalign_type;
		int32_t xalign = sub.xalign;
		int32_t yalign = sub.yalign;
		uint32_t width = sub.subtitle_img->get_width();
		uint32_t height = sub.subtitle_img->get_height();
		if(xalign_type == ALIGN_CUSTOM && xalign < 0)
			oob = true;
		if(xalign_type == ALIGN_CUSTOM && xalign + width > resolution_w)
			oob = true;
		if(width > resolution_w)
			oob = true;
		if(yalign_type == ALIGN_CUSTOM && yalign < 0)
			oob = true;
		if(yalign_type == ALIGN_CUSTOM && yalign + height > resolution_h)
			oob = true;
		if(height > resolution_h)
			oob = true;
		if(oob)
			std::cerr << "WARNING: Subtitles exceed display bounds" << std::endl;
	}

	void init_variables()
	{
		if(!variables.count('\\'))
			variables['\\'] = '\\';
		if(!variables.count('A'))
			variables['A'] = "(unknown)";
		if(!variables.count('R'))
			variables['R'] = "(unknown)";
		if(!variables.count('L'))
			variables['L'] = "(unknown)";
		if(!variables.count('G'))
			variables['G'] = "(unknown)";
	}

	void copy_surface(SDL_Surface* s, unsigned char* buffer, uint32_t total_width, uint32_t y,
		uint32_t align_type, uint32_t extralines)
	{
		uint32_t xalign = 0;
		switch(align_type) {
		case ALIGN_LEFT:
			xalign = 0;
			break;
		case ALIGN_CENTER:
			xalign = (total_width - s->w) / 2;
			break;
		case ALIGN_RIGHT:
			xalign = total_width - s->w;
			break;
		}
		SDL_LockSurface(s);
		for(uint32_t y2 = 0; y2 < s->h + extralines; y2++)
			for(uint32_t x = 0; x < total_width; x++) {
				if(x < xalign || x > xalign + (uint32_t)s->w || y2 >= (uint32_t)s->h)
					buffer[(y + y2) * total_width + x] = 0;
				else
					buffer[(y + y2) * total_width + x] =
						((unsigned char*)s->pixels)[y2 * s->pitch + (x - xalign)];
			}
		SDL_UnlockSurface(s);
	}

	void render_halo(uint32_t w, uint32_t h, unsigned char* data, uint32_t _thickness)
	{
		int32_t thickness = _thickness;
		for(uint32_t y = 0; y < h; y++)
			for(uint32_t x = 0; x < w; x++) {
				bool left = (x > 0) ? (data[y * w + x - 1] == 1) : false;
				bool up = (y > 0) ? (data[y * w + x - w] == 1) : false;
				if(data[y * w + x] != 1)
					continue;

				for(int32_t j = up ? thickness: -thickness; j <= thickness; j++) {
					if(y + j < 0 || y + j >= h)
						continue;
					for(int32_t i = left ? thickness : -thickness; i <= thickness; i++) {
						if(x + i < 0 || x + i >= w)
							continue;
						if(data[(y + j) * w + (x + i)] == 0)
							data[(y + j) * w + (x + i)] = 2;
					}
				}
			}
	}
}

image_frame_rgbx* hardsub_render_settings::operator()()
{
	std::list<SDL_Surface*> lines;
	if(font_name == "")
		throw std::runtime_error("No font set");

	//Initialize SDL_ttf.
	if(!TTF_WasInit() && TTF_Init() < 0) {
		std::stringstream str;
		str << "Can't initialize SDL_ttf: " << TTF_GetError();
		throw std::runtime_error(str.str());
	}

	//Open the font and render the text.
	TTF_Font* font = TTF_OpenFont(font_name.c_str(), font_size);
	try {
		SDL_Color clr;
		clr.r = clr.g = clr.b = 255;
		std::string tmp = "";
		bool escape = false;
		for(size_t i = 0; i < text.length(); i++) {
			char tmp2[2] = {0, 0};
			if(!escape)
				if(text[i] == '\\')
					escape = true;
				else {
					tmp2[0] = text[i];
					tmp += tmp2;
				}
			else if(variables.count(text[i])) {
				tmp += variables[text[i]];
				escape = false;
			} else if(text[i] == 'n') {
				SDL_Surface* s = TTF_RenderUTF8_Solid(font, tmp.c_str(), clr);
				if(!s)
					throw std::runtime_error("Can't render text");
				lines.push_back(s);
				tmp = "";
				escape = false;
			} else {
				std::stringstream str;
				str << "Bad escape character '" << text[i] << "'";
				throw std::runtime_error(str.str());
			}
		}
		if(tmp != "") {
			SDL_Surface* s = TTF_RenderUTF8_Solid(font, tmp.c_str(), clr);
			if(!s)
				throw std::runtime_error("Can't render text");
			lines.push_back(s);
		}
	} catch(...) {
		TTF_CloseFont(font);
		throw;
	}
	TTF_CloseFont(font);

	//Calculate image size and allocate buffers.
	uint32_t total_width = 0;
	uint32_t total_height = 0;
	unsigned char* buffer1 = NULL;
	image_frame_rgbx* img = NULL;
	for(std::list<SDL_Surface*>::iterator i = lines.begin(); i != lines.end(); ++i) {
		if((*i)->w + 2 * halo_thickness > total_width)
			total_width = (*i)->w + 2 * halo_thickness;
		total_height += ((*i)->h + 2 * halo_thickness + spacing);
	}
	try {
		buffer1 = new unsigned char[total_width * total_height];
		img = new image_frame_rgbx(total_width, total_height);
	} catch(...) {
		if(buffer1)
			delete[] buffer1;
		if(img)
			delete img;
	}

	//Copy SDL surfaces to index buffer.
	uint32_t line = 0;
	for(std::list<SDL_Surface*>::iterator i = lines.begin(); i != lines.end(); ++i) {
		copy_surface(*i, buffer1, total_width, line, align_type, spacing + 2 * halo_thickness);
		line += ((*i)->h + spacing + 2 * halo_thickness);
		SDL_FreeSurface(*i);
	}
	render_halo(total_width, total_height, buffer1, halo_thickness);

	//Make full color buffer from indexed buffer.
	uint32_t v0 = 0, v1 = 0, v2 = 0;
	unsigned char* data = img->get_pixels();
	for(uint32_t y = 0; y < total_height; y++)
		for(uint32_t x = 0; x < total_width; x++)
			switch(buffer1[y * total_width + x]) {
			case 0:
				v0++;
				data[y * 4 * total_width + 4 * x + 0] = background_r;
				data[y * 4 * total_width + 4 * x + 1] = background_g;
				data[y * 4 * total_width + 4 * x + 2] = background_b;
				data[y * 4 * total_width + 4 * x + 3] = background_a;
				break;
			case 1:
				v1++;
				data[y * 4 * total_width + 4 * x + 0] = foreground_r;
				data[y * 4 * total_width + 4 * x + 1] = foreground_g;
				data[y * 4 * total_width + 4 * x + 2] = foreground_b;
				data[y * 4 * total_width + 4 * x + 3] = foreground_a;
				break;
			case 2:
				v2++;
				data[y * 4 * total_width + 4 * x + 0] = halo_r;
				data[y * 4 * total_width + 4 * x + 1] = halo_g;
				data[y * 4 * total_width + 4 * x + 2] = halo_b;
				data[y * 4 * total_width + 4 * x + 3] = halo_a;
				break;
			}
	delete[] buffer1;
	return img;
}

void hardsub_settings::reset()
{
	rsettings.font_size = DEFAULT_FONT_SIZE;
	rsettings.halo_thickness =  DEFAULT_HALO_THICKNESS;
	rsettings.foreground_r = DEFAULT_FOREGROUND_R;
	rsettings.foreground_g = DEFAULT_FOREGROUND_G;
	rsettings.foreground_b = DEFAULT_FOREGROUND_B;
	rsettings.foreground_a = DEFAULT_FOREGROUND_A;
	rsettings.halo_r = DEFAULT_HALO_R;
	rsettings.halo_g = DEFAULT_HALO_G;
	rsettings.halo_b = DEFAULT_HALO_B;
	rsettings.halo_a = DEFAULT_HALO_A;
	rsettings.background_r = DEFAULT_BACKGROUND_R;
	rsettings.background_g = DEFAULT_BACKGROUND_G;
	rsettings.background_b = DEFAULT_BACKGROUND_B;
	rsettings.background_a = DEFAULT_BACKGROUND_A;
	rsettings.align_type = DEFAULT_ALIGN_TYPE;
	rsettings.spacing = DEFAULT_SPACING;
	duration = DEFAULT_DURATION;
	xalign_type = DEFAULT_XALIGN_TYPE;
	yalign_type = DEFAULT_YALIGN_TYPE;
}


hardsub_settings::hardsub_settings()
{
	reset();
}

subtitle* hardsub_settings::operator()()
{
	init_variables();
	image_frame_rgbx* img = rsettings();
	try {
		subtitle* sub = new subtitle();
		sub->timecode = timecode;
		sub->duration = duration;
		sub->xalign_type = xalign_type;
		sub->xalign = xalign;
		sub->yalign_type = yalign_type;
		sub->yalign = yalign;
		sub->used_settings = rsettings;
		sub->subtitle_img = img;
		check_in_bounds(*sub);
		return sub;
	} catch(...) {
		delete img;
		throw;
	}
}

void render_subtitle(image_frame_rgbx& bottom, struct subtitle& sub)
{
	int32_t xalign, yalign;

	//Compute X offset.
	switch(sub.xalign_type) {
	case ALIGN_LEFT:
		xalign = 0;
		break;
	case ALIGN_CENTER:
		xalign = ((int32_t)bottom.get_width() - (int32_t)sub.subtitle_img->get_width()) / 2;
		break;
	case ALIGN_RIGHT:
		xalign = (int32_t)bottom.get_width() - (int32_t)sub.subtitle_img->get_width();
		break;
	default:
		xalign = sub.xalign;
	};

	//Compute Y offset.
	switch(sub.yalign_type) {
	case ALIGN_TOP:
		yalign = 0;
		break;
	case ALIGN_CENTER:
		yalign = ((int32_t)bottom.get_height() - (int32_t)sub.subtitle_img->get_height()) / 2;
		break;
	case ALIGN_BOTTOM:
		yalign = (int32_t)bottom.get_height() - (int32_t)sub.subtitle_img->get_height();
		break;
	default:
		yalign = sub.yalign;
	};

	if(xalign < -(int32_t)sub.subtitle_img->get_width() || yalign < -(int32_t)sub.subtitle_img->get_height())
		return;		//Outside image.
	if(xalign >= (int32_t)bottom.get_width() || yalign >= (int32_t)bottom.get_height())
		return;		//Outside image.
	if(sub.subtitle_img->get_width() == 0 || sub.subtitle_img->get_height() == 0)
		return;		//Nothing to draw.

	uint32_t overlay_xoffset = (xalign < 0) ? -xalign : 0;
	uint32_t overlay_yoffset = (yalign < 0) ? -yalign : 0;
	uint32_t overlay_width = sub.subtitle_img->get_width() - overlay_xoffset;
	uint32_t overlay_height = sub.subtitle_img->get_height() - overlay_yoffset;
	if(xalign < 0)
		xalign = 0;
	if(yalign < 0)
		yalign = 0;
	if(xalign + overlay_width > bottom.get_width())
		overlay_width = bottom.get_width() - xalign;
	if(yalign + overlay_height > bottom.get_height())
		overlay_height = bottom.get_height() - yalign;

	for(uint32_t y = 0; y < overlay_height; y++) {
		unsigned char* bottomr = bottom.get_pixels() + ((y + yalign) * 4 * bottom.get_width()) + 4 * xalign;
		unsigned char* overlayr = sub.subtitle_img->get_pixels() + ((y + overlay_yoffset) * 4 *
			sub.subtitle_img->get_width()) + 4 * overlay_xoffset;
		for(uint32_t x = 0; x < overlay_width; x++) {
			uint32_t ibase = x * 4;
			int alpha = overlayr[ibase + 3];
			bottomr[ibase + 0] = (unsigned char)((overlayr[ibase + 0] * alpha + bottomr[ibase + 0] *
				(255 - alpha)) / 255);
			bottomr[ibase + 1] = (unsigned char)((overlayr[ibase + 1] * alpha + bottomr[ibase + 1] *
				(255 - alpha)) / 255);
			bottomr[ibase + 2] = (unsigned char)((overlayr[ibase + 2] * alpha + bottomr[ibase + 2] *
				(255 - alpha)) / 255);
			bottomr[ibase + 3] = 0;
		}
	}
}

std::list<hardsub_settings*> settings_stack;

namespace
{
	int64_t signed_settingvalue(const std::string& _setting, int64_t limit_low, int64_t limit_high)
	{
		std::string setting = settingvalue(_setting);
		int64_t parsed = 0;
		uint64_t rawparsed = 0;
		bool negative = false;
		size_t index = 0;
		if(setting.length() > 0 && setting[0] == '-') {
			negative = true;
			index++;
		}
		if(index == setting.length())
			throw std::runtime_error("Bad number");

		for(; index < setting.length(); index++) {
			if(setting[index] < '0' || setting[index] > '9')
				throw std::runtime_error("Bad number");
			if(rawparsed >= 0xFFFFFFFFFFFFFFFFULL / 10)
				throw std::runtime_error("Number absolute value too large");
			rawparsed = 10 * rawparsed + (setting[index] - '0');
		}

		//Take negation if needed and check range.
		if(!negative)
			if(rawparsed > 0x7FFFFFFFFFFFFFFFULL)
				throw std::runtime_error("Value overflows");
			else
				parsed = rawparsed;
		else
			if(rawparsed > 0x8000000000000000ULL)
				throw std::runtime_error("Value underflows");
			else if(rawparsed == 0x8000000000000000ULL)
				parsed = 2 * -(int64_t)(1ULL << 62);
			else
				parsed = -(int64_t)rawparsed;

		if(parsed < limit_low || parsed > limit_high)
			throw std::runtime_error("Value outside valid range");
		return parsed;
	}

	uint64_t time_settingvalue(const std::string& setting)
	{
		std::string v = settingvalue(setting);
		return parse_timespec(v);
	}

	void color_settingvalue(const std::string& setting, uint8_t& r, uint8_t& g, uint8_t& b, uint8_t& a)
	{
		std::string v = settingvalue(setting);
		uint8_t c[4];
		size_t component = 0;
		size_t index = 0;
		while(index < v.length()) {
			if(component > 3)
				throw std::runtime_error("Bad color specification");

			//Find the start of next component and component length.
			size_t tmp = v.find_first_of(",", index);
			size_t cstart = index;
			size_t clength = 0;
			if(tmp > v.length()) {
				index = v.length();
				clength = v.length() - cstart;
			} else {
				index = tmp + 1;
				clength = tmp - cstart;
			}

			uint16_t value = 0;
			if(clength == 0 || clength > 3)
				throw std::runtime_error("Bad color specification");
			for(size_t i = 0; i < clength; i++)
				if(v[cstart + i] < '0' || v[cstart + i] > '9')
					throw std::runtime_error("Bad color specification");
				else
					value = value * 10 + (v[cstart + i] - '0');
			if(value > 255)
				throw std::runtime_error("Bad color specification");
			c[component] = value;

			component++;
		}
		if(component == 0)
			throw std::runtime_error("Bad color specification");
		else if(component == 1) {
			a = c[0];
		} else if(component == 2) {
			r = g = b = c[0];
			a = c[1];
		} else if(component == 3) {
			r = c[0];
			g = c[1];
			b = c[2];
		} else if(component == 4) {
			r = c[0];
			g = c[1];
			b = c[2];
			a = c[3];
		}
	}

	subtitle*  parse_subtitle_option_one(struct hardsub_settings& settings, const std::string& option)
	{
		if(isstringprefix(option, "font="))
			settings.rsettings.font_name = settingvalue(option);
		else if(isstringprefix(option, "size="))
			settings.rsettings.font_size = signed_settingvalue(option, 1, 10000);
		else if(isstringprefix(option, "spacing="))
			settings.rsettings.spacing = signed_settingvalue(option, 0, 10000);
		else if(isstringprefix(option, "duration="))
			settings.duration = time_settingvalue(option);
		else if(option == "xpos=left")
			settings.xalign_type = ALIGN_LEFT;
		else if(option == "xpos=center")
			settings.xalign_type = ALIGN_CENTER;
		else if(option == "xpos=right")
			settings.xalign_type = ALIGN_RIGHT;
		else if(isstringprefix(option, "xpos=")) {
			settings.xalign = signed_settingvalue(option, -2000000000, 2000000000);
			settings.xalign_type = ALIGN_CUSTOM;
		} else if(option == "ypos=top")
			settings.yalign_type = ALIGN_TOP;
		else if(option == "ypos=center")
			settings.yalign_type = ALIGN_CENTER;
		else if(option == "ypos=bottom")
			settings.yalign_type = ALIGN_BOTTOM;
		else if(isstringprefix(option, "ypos=")) {
			settings.yalign = signed_settingvalue(option, -2000000000, 2000000000);
			settings.yalign_type = ALIGN_CUSTOM;
		} else if(isstringprefix(option, "halo="))
			settings.rsettings.halo_thickness = signed_settingvalue(option, 0, 1000);
		else if(option == "textalign=left")
			settings.rsettings.align_type = ALIGN_LEFT;
		else if(option == "textalign=center")
			settings.rsettings.align_type = ALIGN_CENTER;
		else if(option == "textalign=right")
			settings.rsettings.align_type = ALIGN_RIGHT;
		else if(option == "reset")
			settings.reset();
		else if(option == "push")
			settings_stack.push_back(new hardsub_settings(settings));
		else if(option == "pop") {
			if(settings_stack.empty())
				throw std::runtime_error("Attempt to pop empty stack");
			hardsub_settings* s = settings_stack.back();
			settings_stack.pop_back();
			settings = *s;
			delete s;
		} else if(isstringprefix(option, "text=")) {
			std::string tmp = settingvalue(option);
			size_t split = tmp.find_first_of(",");
			if(split > tmp.length())
				throw std::runtime_error("Bad text syntax");
			settings.timecode = parse_timespec(tmp.substr(0, split));
			settings.rsettings.text = tmp.substr(split + 1);
			return settings();
		} else if(isstringprefix(option, "foreground-color=")) {
			uint8_t r = 255, g = 255, b = 255, a = 255;
			color_settingvalue(option, r, g, b, a);
			settings.rsettings.foreground_r = r;
			settings.rsettings.foreground_g = g;
			settings.rsettings.foreground_b = b;
			settings.rsettings.foreground_a = a;
		} else if(isstringprefix(option, "halo-color=")) {
			uint8_t r = 0, g = 0, b = 0, a = 255;
			color_settingvalue(option, r, g, b, a);
			settings.rsettings.halo_r = r;
			settings.rsettings.halo_g = g;
			settings.rsettings.halo_b = b;
			settings.rsettings.halo_a = a;
		} else if(isstringprefix(option, "background-color=")) {
			uint8_t r = 0, g = 0, b = 0, a = 0;
			color_settingvalue(option, r, g, b, a);
			settings.rsettings.background_r = r;
			settings.rsettings.background_g = g;
			settings.rsettings.background_b = b;
			settings.rsettings.background_a = a;
		} else
			throw std::runtime_error("Unknown subtitle option");
		return NULL;
	}
}


std::list<subtitle*> parse_subtitle_option(struct hardsub_settings& settings, const std::string& option)
{
	std::list<subtitle*> list;

	if(isstringprefix(option, "script=")) {
		std::string filename = settingvalue(option);
		std::ifstream stream(filename.c_str());
		if(!stream)
			throw std::runtime_error("Can't open script file");
		while(stream) {
			std::string opt;
			std::getline(stream, opt);
			if(opt == "")
				continue;
			subtitle* x = parse_subtitle_option_one(settings, opt);
			if(x)
				list.push_back(x);
		}
		if(!stream.eof() && (stream.bad() || stream.fail()))
			throw std::runtime_error("Can't read script file");
	} else {
		subtitle* x = parse_subtitle_option_one(settings, option);
		if(x)
			list.push_back(x);
	}

	return list;
}

void print_hardsubs_help(const std::string& prefix)
{
	std::cout << prefix << "font=<file>" << std::endl;
	std::cout << "\tUse the specified font." << std::endl;
	std::cout << prefix << "size=<size>" << std::endl;
	std::cout << "\tUse the specified font size (default 16)." << std::endl;
	std::cout << prefix << "xpos=<pixels>" << std::endl;
	std::cout << "\tUse the specified subtitle x-offset." << std::endl;
	std::cout << prefix << "xpos=left" << std::endl;
	std::cout << "\tPlace subtitles to left." << std::endl;
	std::cout << prefix << "xpos=center" << std::endl;
	std::cout << "\tPlace subtitles to center (default)." << std::endl;
	std::cout << prefix << "xpos=right" << std::endl;
	std::cout << "\tPlace subtitles to right." << std::endl;
	std::cout << prefix << "ypos=<pixels>" << std::endl;
	std::cout << "\tUse the specified subtitle y-offset." << std::endl;
	std::cout << prefix << "ypos=top" << std::endl;
	std::cout << "\tPlace subtitles to top." << std::endl;
	std::cout << prefix << "ypos=center" << std::endl;
	std::cout << "\tPlace subtitles to center." << std::endl;
	std::cout << prefix << "ypos=bottom" << std::endl;
	std::cout << "\tPlace subtitles to bottom (default)." << std::endl;
	std::cout << prefix << "duration=<duration>" << std::endl;
	std::cout << "\tSubtitles last <duration>." << std::endl;
	std::cout << prefix << "halo=<thickness>" << std::endl;
	std::cout << "\tSubtitle halo thickness <thickness>." << std::endl;
	std::cout << prefix << "foreground-color=<a>" << std::endl;
	std::cout << prefix << "foreground-color=<rgb>,<a>" << std::endl;
	std::cout << prefix << "foreground-color=<r>,<g>,<b>" << std::endl;
	std::cout << prefix << "foreground-color=<r>,<g>,<b>,<a>" << std::endl;
	std::cout << "\tSet foreground color. Default is fully opaque white." << std::endl;
	std::cout << prefix << "halo-color=<a>" << std::endl;
	std::cout << prefix << "halo-color=<rgb>,<a>" << std::endl;
	std::cout << prefix << "halo-color=<r>,<g>,<b>" << std::endl;
	std::cout << prefix << "halo-color=<r>,<g>,<b>,<a>" << std::endl;
	std::cout << "\tSet halo color. Default is fully opaque black." << std::endl;
	std::cout << prefix << "background-color=<a>" << std::endl;
	std::cout << prefix << "background-color=<rgb>,<a>" << std::endl;
	std::cout << prefix << "background-color=<r>,<g>,<b>" << std::endl;
	std::cout << prefix << "background-color=<r>,<g>,<b>,<a>" << std::endl;
	std::cout << "\tSet background color. Default is fully transparent black." << std::endl;
	std::cout << prefix << "textalign=left" << std::endl;
	std::cout << prefix << "textalign=center" << std::endl;
	std::cout << prefix << "textalign=right" << std::endl;
	std::cout << "\tSet text alignment between lines. Default is center." << std::endl;
	std::cout << prefix << "spacing=<spacing>" << std::endl;
	std::cout << "\tSet text spacing between lines. Default is 1." << std::endl;
	std::cout << prefix << "text=<timecode>,<text>" << std::endl;
	std::cout << "\tDisplay <text> at <timecode>. '\\\\' stands for backslash," << std::endl;
	std::cout << "\t'\\n' stands for newline." << std::endl;
	std::cout << prefix << "reset" << std::endl;
	std::cout << "\tReset to defaults." << std::endl;
	std::cout << prefix << "push" << std::endl;
	std::cout << "\tPush current settings to stack." << std::endl;
	std::cout << prefix << "pop" << std::endl;
	std::cout << "\tPop settings from stack." << std::endl;
	std::cout << prefix << "script=<file>" << std::endl;
	std::cout << "\tRead subtitle commands from <file> and do them." << std::endl;
}

std::list<subtitle*> process_hardsubs_options(struct hardsub_settings& settings, const std::string& prefix, int argc, char** argv)
{
	std::list<subtitle*> global;
	for(int i = 1; i < argc; i++) {
		std::string arg = argv[i];
		if(arg == "--")
			break;
		if(!isstringprefix(arg, prefix))
			continue;
		try {
			std::list<subtitle*> local_list;
			local_list = parse_subtitle_option(settings, arg.substr(prefix.length()));
			for(std::list<subtitle*>::iterator i = local_list.begin(); i != local_list.end(); i++)
				global.push_back(*i);
		} catch(std::exception& e) {
			std::stringstream str;
			str << "Error processing option '" << arg << "': " << e.what();
			throw std::runtime_error(str.str());
		}
	}
	return global;
}

namespace
{
	std::string decimalstring_frombinary(const std::vector<unsigned char>& num, bool ssh_style)
	{
		std::vector<unsigned char> reverse;
		unsigned brounds = ssh_style ? 7 : 8;
		size_t length = num.size();
		if(ssh_style) {
			for(length = 0; length < num.size(); length++)
				if(num[length] < 128)
					break;
			length++;
			if(length > num.size())
				throw std::runtime_error("Invalid ssh-style mpint");
		}
		reverse.push_back('0');
		for(size_t i = 0; i < length; i++) {
			for(size_t j = 0; j < brounds; j++) {
				bool carry = ((num[i] & (1 << (brounds - 1 - j))) != 0);
				for(size_t k = 0; k < reverse.size(); k++) {
					unsigned x = 2 * (reverse[k] - '0') + (carry ? 1 : 0);
					carry = (x > 9);
					reverse[k] = '0' + x % 10;
				}
				if(carry)
					reverse.push_back('1');
			}
		}
		std::string final(reverse.size(), '0');
		std::copy(reverse.rbegin(), reverse.rend(), final.begin());
		return final;
	}
}

void subtitle_process_gameinfo(std::list<subtitle*>& subs, struct packet& p)
{
	if(p.rp_minor == 'A' || p.rp_minor == 'G') {
		std::stringstream str;
		for(size_t i = 0; i < p.rp_payload.size(); i++)
			str << p.rp_payload[i];
		std::string newarg = str.str();
		subtitle_update_parameter(subs, p.rp_minor, newarg);
	} else if(p.rp_minor == 'R') {
		subtitle_update_parameter(subs, p.rp_minor, decimalstring_frombinary(p.rp_payload, false));
	} else if(p.rp_minor == 'L') {
		std::stringstream str;
		uint64_t v = 0;
		if(p.rp_payload.size() < 8)
			return;
		v |= (uint64_t)p.rp_payload[0] << 56;
		v |= (uint64_t)p.rp_payload[1] << 48;
		v |= (uint64_t)p.rp_payload[2] << 40;
		v |= (uint64_t)p.rp_payload[3] << 32;
		v |= (uint64_t)p.rp_payload[4] << 24;
		v |= (uint64_t)p.rp_payload[5] << 16;
		v |= (uint64_t)p.rp_payload[6] << 8;
		v |= (uint64_t)p.rp_payload[7];
		v = (v + 999999) / 1000000;
		uint64_t hours = v / 3600000;
		v %= 3600000;
		uint64_t minutes = v / 60000;
		v %= 60000;
		uint64_t seconds = v / 1000;
		v %= 1000;
		if(hours > 0)
			str << hours << ":";
		str << std::setfill('0') << std::setw(2) << minutes << ":" << std::setfill('0')
			<< std::setw(2) << seconds << "." << std::setfill('0') << std::setw(3) << v;
		std::string newarg = str.str();
		subtitle_update_parameter(subs, p.rp_minor, newarg);
	} else {
		std::cerr << "WARNING: Unknown gameinfo type " << (unsigned)p.rp_minor << "." << std::endl;
	}
}

subtitle::~subtitle()
{
}

void subtitle_update_parameter(std::list<subtitle*>& subs, unsigned char parameter, const std::string& value)
{
	variables[parameter] = value;
	for(std::list<subtitle*>::iterator i = subs.begin(); i != subs.end(); ++i)
		try {
			image_frame_rgbx* subtitle_img = (*i)->subtitle_img;
			(*i)->subtitle_img = (*i)->used_settings();
			delete subtitle_img;
			check_in_bounds(**i);
		} catch(std::exception& e) {
			std::cerr << "WARNING: Can't update subtitles: " << e.what() << std::endl;
		}
}

void subtitle_set_resolution(uint32_t w, uint32_t h)
{
	resolution_w = w;
	resolution_h = h;
}

#ifdef SUBTITLE_TEST


int real_main(int argc, char** argv)
{
	std::list<subtitle*> list, list2;
	hardsub_settings s;
	for(int i = 1; i < argc; i++) {
		list2 = parse_subtitle_option(s, argv[i]);
		for(std::list<subtitle*>::iterator j = list2.begin(); j != list2.end(); ++j)
			list.push_back(*j);
	}

	SDL_Init(SDL_INIT_EVERYTHING);
	for(std::list<subtitle*>::iterator i = list.begin(); i != list.end(); ++i) {
		image_frame_rgbx& _i = *((*i)->subtitle_img);
		uint32_t iwidth = _i.get_width();
		const unsigned char* idata = _i.get_pixels();
		SDL_Surface* s = SDL_SetVideoMode(_i.get_width(), _i.get_height(), 32, SDL_SWSURFACE | SDL_DOUBLEBUF);

		SDL_LockSurface(s);
		for(uint32_t y = 0; y < _i.get_height(); y++)
			for(uint32_t x = 0; x < iwidth; x++) {
				((unsigned char*)s->pixels)[y * s->pitch + 4 * x + 0] =
					idata[y * 4 * iwidth + 4 * x + 0];
				((unsigned char*)s->pixels)[y * s->pitch + 4 * x + 1] =
					idata[y * 4 * iwidth + 4 * x + 1];
				((unsigned char*)s->pixels)[y * s->pitch + 4 * x + 2] =
					idata[y * 4 * iwidth + 4 * x + 2];
				((unsigned char*)s->pixels)[y * s->pitch + 4 * x + 3] =
					idata[y * 4 * iwidth + 4 * x + 3];
			}
		SDL_UnlockSurface(s);

		SDL_Flip(s);
		SDL_Event e;
wait_loop:
		if(!SDL_WaitEvent(&e))
			std::cerr << "Can't wait for event" << std::endl;
		else if(e.type == SDL_QUIT)
			continue;
		goto wait_loop;
	}
	SDL_Quit();

	return 0;
}

#endif
