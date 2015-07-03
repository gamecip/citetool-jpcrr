#include <cstring>
#include "outputs/internal.hpp"
#include "outputs/argexpand.hpp"
#include "cscd.hpp"
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <vector>
#include <string>
#include <sstream>

namespace
{
	class output_driver_cscd : public output_driver
	{
	public:
		output_driver_cscd(const std::string& _filename, unsigned _level, unsigned long _maxsegframes)
		{
			filename = _filename;
			level = _level;
			maxsegframes = _maxsegframes;
			set_video_callback(make_bound_method(*this, &output_driver_cscd::video_callback));
			set_audio_callback(make_bound_method(*this, &output_driver_cscd::audio_callback));
		}

		~output_driver_cscd()
		{
			dumper->end();
			delete dumper;
		}

		void ready()
		{
			const video_settings& v = get_video_settings();
			const audio_settings& a = get_audio_settings();
			avi_cscd_dumper::global_parameters gp;
			avi_cscd_dumper::segment_parameters sp;
			gp.sampling_rate = a.get_rate();
			gp.channel_count = 2;
			gp.audio_16bit = true;
			sp.fps_n = v.get_rate_num();
			sp.fps_d = v.get_rate_denum();
			if(!sp.fps_n || !sp.fps_d) {
				sp.fps_n = 60;
				sp.fps_d = 1;
			}
			sp.dataformat = avi_cscd_dumper::PIXFMT_RGBX;
			sp.width = v.get_width();
			sp.height = v.get_height();
			sp.default_stride = true;
			sp.stride = 4 * v.get_width();
			sp.keyframe_distance = (level > 9) ? 300 : 1;
			sp.deflate_level = (level > 9) ? (1+ level % 10) : level;
			sp.max_segment_frames = maxsegframes;
			dumper = new avi_cscd_dumper(filename, gp, sp);
		}

		void video_callback(uint64_t timestamp, const uint8_t* raw_rgbx_data)
		{
			dumper->video(raw_rgbx_data);
			dumper->wait_frame_processing();
		}

		void audio_callback(short left, short right)
		{
			dumper->audio(&left, &right, 1, avi_cscd_dumper::SNDFMT_SIGNED_16NE);
		}
	private:
		FILE* out;
		std::string filename;
		unsigned level;
		avi_cscd_dumper* dumper;
		unsigned long maxsegframes;
	};

	class output_driver_cscd_factory : output_driver_factory
	{
	public:
		output_driver_cscd_factory()
			: output_driver_factory("cscd")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			unsigned level = 7;
			unsigned long maxsegframes = 0;
			std::string p = parameters;
			while(p != "") {
				size_t s = p.find_first_of(",");
				std::string n;
				std::string x;
				if(s < p.length()) {
					x = p.substr(0, s);
					n = p.substr(s + 1);
				} else {
					x = p;
					n = "";
				}
				p = n;
				if(x.substr(0, 6) == "level=") {
					std::string y = x.substr(6);
					char* e;
					level = strtoul(y.c_str(), &e, 10);
					if(level > 18 || *e)
						throw std::runtime_error("Bad compression level");
				}
				if(x.substr(0, 13) == "maxsegframes=") {
					std::string y = x.substr(13);
					char* e;
					maxsegframes = strtoul(y.c_str(), &e, 10);
					if(*e)
						throw std::runtime_error("Bad maxsegframes");
				}
			}
			return *new output_driver_cscd(name, level, maxsegframes);
		}
	} factory1;
}
