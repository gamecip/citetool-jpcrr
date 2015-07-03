#include "outputs/internal.hpp"
#include <cstdio>
#include <stdexcept>
#include <string>
#include <sstream>
#include <fcntl.h>

namespace
{
	std::string expand_options(const std::string& opts)
	{
		bool insert = true;
		std::ostringstream ret;
		for(size_t i = 0; i < opts.length(); i++) {
			if(insert)
				ret << " -";
			insert = false;
			switch(opts[i]) {
			case ',':
				insert = true;
				break;
			case '=':
				ret << " ";
				break;
			default:
				ret << opts[i];
			};
		}
		ret << " ";
		return ret.str();
	}

	class output_driver_faac : public output_driver
	{
	public:
		output_driver_faac(const std::string& _filename, const std::string& _options)
		{
			filename = _filename;
			options = _options;
			set_audio_callback(make_bound_method(*this, &output_driver_faac::audio_callback));
		}

		~output_driver_faac()
		{
			pclose(out);
		}

		void ready()
		{
			const audio_settings& a = get_audio_settings();

			std::stringstream commandline;
			commandline << "faac -P -C 2 -R " << a.get_rate() << " ";
			commandline << expand_options(options);
			commandline << "-o " << filename << " -";
			std::string s = commandline.str();
			out = popen(s.c_str(), "w");
			if(!out) {
				std::stringstream str;
				str << "Can't run faac (" << s << ")";
				throw std::runtime_error(str.str());
			}
#if defined(_WIN32) || defined(_WIN64)
			setmode(fileno(out), O_BINARY);
#endif
		}

		void audio_callback(short left, short right)
		{
			uint8_t rawdata[4];
			rawdata[1] = ((unsigned short)left >> 8) & 0xFF;
			rawdata[0] = ((unsigned short)left) & 0xFF;
			rawdata[3] = ((unsigned short)right >> 8) & 0xFF;
			rawdata[2] = ((unsigned short)right) & 0xFF;
			if(fwrite(rawdata, 1, 4, out) < 4)
				throw std::runtime_error("Error writing sample to faac");
		}
	private:
		FILE* out;
		std::string filename;
		std::string options;
	};

	class output_driver_faac_factory : output_driver_factory
	{
	public:
		output_driver_faac_factory()
			: output_driver_factory("faac")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			return *new output_driver_faac(name, parameters);
		}
	} factory;
}
